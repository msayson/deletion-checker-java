package com.marksayson.deletionchecker.format;

import com.marksayson.deletionchecker.UnsignedBytes;
import com.marksayson.deletionchecker.checksum.Checksums;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Serializes one entity type's identifiers into a packed binary file (format v2): header, prefix
 * index, Bloom filter, identifier offset table, identifier data, with the file's CRC32C patched into
 * the header.
 *
 * <p>Build-time only — never invoked on the lookup path. Callers pass identifiers already sorted by
 * unsigned bytes and deduplicated; {@link #write} verifies both and rejects violations rather than
 * producing a file that would fail mysteriously at query time.
 */
public final class PackedFileWriter {

    /** Default Bloom-filter false-positive rate — see {@link #write(String, List, int, double)}. */
    public static final double DEFAULT_BLOOM_FPR = 0.01;

    private PackedFileWriter() {
    }

    /**
     * Serializes a packed file using {@link PrefixIndex#DEFAULT_BUCKET_SIZE} and
     * {@link #DEFAULT_BLOOM_FPR}.
     *
     * @param entityType the entity type this file holds; ASCII, 1 to 64 bytes
     * @param sortedIdentifiers the identifiers, already sorted by unsigned bytes and deduplicated
     * @return the complete file bytes
     */
    public static byte[] write(final String entityType, final List<byte[]> sortedIdentifiers) {
        return write(entityType, sortedIdentifiers, PrefixIndex.DEFAULT_BUCKET_SIZE);
    }

    /**
     * Serializes a packed file with {@link #DEFAULT_BLOOM_FPR}.
     *
     * @param entityType the entity type this file holds; ASCII, 1 to 64 bytes
     * @param sortedIdentifiers the identifiers, already sorted by unsigned bytes and deduplicated
     * @param bucketSize the prefix index bucket target size {@code K}; must be at least 1
     * @return the complete file bytes
     */
    public static byte[] write(
            final String entityType, final List<byte[]> sortedIdentifiers, final int bucketSize) {
        return write(entityType, sortedIdentifiers, bucketSize, DEFAULT_BLOOM_FPR);
    }

    /**
     * Serializes a packed file.
     *
     * @param entityType the entity type this file holds; ASCII, 1 to 64 bytes
     * @param sortedIdentifiers the identifiers, already sorted by unsigned bytes and deduplicated
     * @param bucketSize the prefix index bucket target size {@code K}; must be at least 1
     * @param bloomFpr the Bloom filter's target false-positive rate; {@code >= 1} disables the
     *     filter, otherwise must be positive
     * @return the complete file bytes, with the CRC32C written into the header
     * @throws IllegalArgumentException if {@code sortedIdentifiers} is not strictly ascending by
     *     unsigned bytes, if {@code entityType} is not 1 to 64 ASCII bytes, if {@code bucketSize} is
     *     less than 1, or if {@code bloomFpr} is not positive
     */
    public static byte[] write(
            final String entityType, final List<byte[]> sortedIdentifiers, final int bucketSize,
            final double bloomFpr) {
        requireStrictlyAscending(sortedIdentifiers);
        if (!(bloomFpr > 0.0)) {
            throw new IllegalArgumentException("bloomFpr must be positive: " + bloomFpr);
        }

        final int count = sortedIdentifiers.size();
        final PrefixIndex index = PrefixIndex.build(sortedIdentifiers, bucketSize);
        final int[] startIndex = index.startIndex();
        final int[] separatorOffset = index.separatorOffset();
        final byte[] separatorData = index.separatorData();
        final int[] identifierOffset = OffsetTableBuilder.cumulativeOffsets(sortedIdentifiers);

        final int bloomBlockCount = BloomFilter.blockCountFor(count, bloomFpr);
        final byte[] bloom = BloomFilter.build(sortedIdentifiers, bloomBlockCount);
        requireNoFalseNegatives(sortedIdentifiers, bloom, bloomBlockCount);

        final int prefixTableBytes = Integer.BYTES * (index.bucketCount() + 1);
        final int fileSize = PackedFileFormat.HEADER_SIZE
                + prefixTableBytes
                + prefixTableBytes
                + separatorData.length
                + bloom.length
                + Integer.BYTES * (count + 1)
                + identifierOffset[count];

        final ByteBuffer buffer = ByteBuffer.allocate(fileSize).order(PackedFileFormat.BYTE_ORDER);

        new Header(
                PackedFileFormat.FORMAT_VERSION, entityType, count, bucketSize,
                index.bucketCount(), 0L, bloomBlockCount)
                .writeTo(buffer);

        buffer.position(PackedFileFormat.HEADER_SIZE);
        for (final int value : startIndex) {
            buffer.putInt(value);
        }
        for (final int value : separatorOffset) {
            buffer.putInt(value);
        }
        buffer.put(separatorData);
        buffer.put(bloom);
        for (final int value : identifierOffset) {
            buffer.putInt(value);
        }
        for (final byte[] identifier : sortedIdentifiers) {
            buffer.put(identifier);
        }

        final long checksum = Checksums.crc32cWithFieldZeroed(
                buffer, PackedFileFormat.CHECKSUM_OFFSET, PackedFileFormat.CHECKSUM_LENGTH);
        buffer.putInt(PackedFileFormat.CHECKSUM_OFFSET, (int) checksum);

        return buffer.array();
    }

    private static void requireStrictlyAscending(final List<byte[]> identifiers) {
        for (int i = 1; i < identifiers.size(); i++) {
            final int comparison =
                    UnsignedBytes.lexicographicalCompare(identifiers.get(i - 1), identifiers.get(i));
            if (comparison > 0) {
                throw new IllegalArgumentException(
                        "identifiers are not sorted by unsigned bytes at index " + i);
            }
            if (comparison == 0) {
                throw new IllegalArgumentException("identifiers contain a duplicate at index " + i);
            }
        }
    }

    /** A Bloom filter has no false negatives — every inserted identifier must read back as present. */
    static void requireNoFalseNegatives(
            final List<byte[]> identifiers, final byte[] bloom, final int blockCount) {
        final BloomFilter filter = BloomFilter.view(
                ByteBuffer.wrap(bloom).order(PackedFileFormat.BYTE_ORDER), 0, blockCount);
        if (filter == null) {
            return;
        }
        for (final byte[] identifier : identifiers) {
            if (!filter.mightContain(identifier)) {
                throw new IllegalStateException("Bloom filter false negative while building");
            }
        }
    }
}
