package com.marksayson.deletionchecker.format;

import com.marksayson.deletionchecker.checksum.Checksums;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One entity type's packed file, memory-mapped and queried for exact membership.
 *
 * <p>{@link #open} maps the file, checks the magic bytes and format version, verifies the whole-file
 * CRC32C, confirms the header's entity type, and lifts the small prefix-index tables onto the heap
 * (they are hot and reused by every query); the large identifier offset table and identifier data
 * stay in the mapping. The mapping is held for the life of this object — the dataset is immutable
 * and there is no {@code close}.
 *
 * <p>{@link #contains} reads the mapping by absolute index only and holds no mutable state, so an
 * instance is safe for concurrent callers.
 */
public final class PackedDeletionSet {

    private final ByteBuffer data;
    private final PrefixIndex prefixIndex;
    private final String entityType;
    private final int identifierCount;
    private final long checksum;
    private final int identifierOffsetTableStart;
    private final int identifierDataStart;

    private PackedDeletionSet(
            final ByteBuffer data,
            final PrefixIndex prefixIndex,
            final String entityType,
            final int identifierCount,
            final long checksum,
            final int identifierOffsetTableStart,
            final int identifierDataStart) {
        this.data = data;
        this.prefixIndex = prefixIndex;
        this.entityType = entityType;
        this.identifierCount = identifierCount;
        this.checksum = checksum;
        this.identifierOffsetTableStart = identifierOffsetTableStart;
        this.identifierDataStart = identifierDataStart;
    }

    /**
     * Maps and validates the packed file at {@code path}.
     *
     * @param path the packed file
     * @param expectedEntityType the entity type the caller expects this file to hold (from the
     *     manifest)
     * @return a queryable view of the file
     * @throws IOException if the file cannot be opened or mapped
     * @throws CorruptDatasetException if the file is truncated, its magic bytes are wrong, its
     *     checksum does not match, or its entity type is not {@code expectedEntityType}
     * @throws UnsupportedFormatVersionException if the file's format version is not the one this
     *     build reads
     */
    public static PackedDeletionSet open(final Path path, final String expectedEntityType)
            throws IOException {
        final MappedByteBuffer mapped;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        }
        final ByteBuffer data = mapped.order(PackedFileFormat.BYTE_ORDER);

        final Header header;
        try {
            header = Header.readFrom(data);
        } catch (final IndexOutOfBoundsException e) {
            throw new CorruptDatasetException("file is smaller than the fixed header");
        }

        final long computedChecksum = Checksums.crc32cWithFieldZeroed(
                data, PackedFileFormat.CHECKSUM_OFFSET, PackedFileFormat.CHECKSUM_LENGTH);
        if (computedChecksum != header.checksum()) {
            throw new CorruptDatasetException(
                    "checksum mismatch: header says " + header.checksum()
                            + ", file computes " + computedChecksum);
        }
        if (!header.entityType().equals(expectedEntityType)) {
            throw new CorruptDatasetException(
                    "entityType mismatch: file holds '" + header.entityType()
                            + "', expected '" + expectedEntityType + "'");
        }

        // The file is intact and CRC-valid, so the section sizes implied by the header are exact.
        final int bucketCount = header.bucketCount();
        final int identifierCount = header.identifierCount();

        final int startIndexStart = PackedFileFormat.HEADER_SIZE;
        final int[] startIndex = readInts(data, startIndexStart, bucketCount + 1);

        final int separatorOffsetStart = startIndexStart + Integer.BYTES * (bucketCount + 1);
        final int[] separatorOffset = readInts(data, separatorOffsetStart, bucketCount + 1);

        final int separatorDataStart = separatorOffsetStart + Integer.BYTES * (bucketCount + 1);
        final byte[] separatorData = new byte[separatorOffset[bucketCount]];
        data.get(separatorDataStart, separatorData);

        final int identifierOffsetTableStart = separatorDataStart + separatorData.length;
        final int identifierDataStart =
                identifierOffsetTableStart + Integer.BYTES * (identifierCount + 1);

        final PrefixIndex prefixIndex = PrefixIndex.fromParts(
                identifierCount, bucketCount, startIndex, separatorOffset, separatorData);

        return new PackedDeletionSet(
                data, prefixIndex, header.entityType(), identifierCount, header.checksum(),
                identifierOffsetTableStart, identifierDataStart);
    }

    /**
     * Returns whether {@code id} is recorded as deleted for this entity type.
     *
     * @param id the identifier to check, UTF-8 encoded
     * @return {@code true} if {@code id} is present, {@code false} otherwise
     */
    public boolean contains(final byte[] id) {
        if (identifierCount == 0) {
            return false;
        }
        final int bucket = prefixIndex.selectBucket(id);
        return BinarySearch.contains(
                id, data, identifierOffsetTableStart, identifierDataStart,
                prefixIndex.bucketStart(bucket), prefixIndex.bucketEnd(bucket));
    }

    /**
     * Returns the entity type this file holds, as read from its header.
     *
     * @return the entity type
     */
    public String entityType() {
        return entityType;
    }

    /**
     * Returns the number of identifiers in this file.
     *
     * @return the identifier count
     */
    public int identifierCount() {
        return identifierCount;
    }

    /**
     * Returns this file's CRC32C, as read from its header and already verified against the file's
     * actual bytes by {@link #open}, as an unsigned 32-bit value in the low bits of the result.
     *
     * @return the file checksum
     */
    public long checksum() {
        return checksum;
    }

    private static int[] readInts(final ByteBuffer data, final int start, final int count) {
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = data.getInt(start + i * Integer.BYTES);
        }
        return values;
    }
}
