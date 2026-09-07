package com.marksayson.deletionchecker.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The fixed-size header of a packed entity-type file.
 *
 * @param formatVersion the binary layout version (see {@link PackedFileFormat#FORMAT_VERSION})
 * @param entityType the entity type this file holds; ASCII, 1 to 64 bytes
 * @param identifierCount the number of unique identifiers in the file
 * @param bucketSize the prefix index bucket target size {@code K}
 * @param bucketCount the number of prefix index buckets
 * @param checksum the CRC32C of the whole file with this field zeroed, as an unsigned 32-bit value
 */
record Header(
        int formatVersion,
        String entityType,
        int identifierCount,
        int bucketSize,
        int bucketCount,
        long checksum) {

    Header {
        Objects.requireNonNull(entityType, "entityType");
        final int length = entityType.length();
        if (length == 0) {
            throw new IllegalArgumentException("entityType must not be empty");
        }
        if (length > PackedFileFormat.MAX_ENTITY_TYPE_BYTES) {
            throw new IllegalArgumentException(
                    "entityType is " + length + " bytes, over the "
                            + PackedFileFormat.MAX_ENTITY_TYPE_BYTES + "-byte maximum");
        }
        for (int i = 0; i < length; i++) {
            if (entityType.charAt(i) > 0x7F) {
                throw new IllegalArgumentException(
                        "entityType must be ASCII; non-ASCII character at index " + i);
            }
        }
    }

    /**
     * Writes this header, little-endian, into the first {@link PackedFileFormat#HEADER_SIZE} bytes of
     * {@code target}. Uses absolute indexing; {@code target}'s position, limit, and byte order are
     * left unchanged.
     *
     * @param target a buffer with capacity for at least a full header
     */
    void writeTo(final ByteBuffer target) {
        final byte[] entityTypeBytes = entityType.getBytes(StandardCharsets.US_ASCII);
        final byte[] entityTypeField = new byte[PackedFileFormat.MAX_ENTITY_TYPE_BYTES];
        System.arraycopy(entityTypeBytes, 0, entityTypeField, 0, entityTypeBytes.length);

        final ByteBuffer buffer = target.duplicate().order(PackedFileFormat.BYTE_ORDER);
        buffer.put(PackedFileFormat.MAGIC_OFFSET, PackedFileFormat.magic());
        buffer.putInt(PackedFileFormat.FORMAT_VERSION_OFFSET, formatVersion);
        buffer.putInt(PackedFileFormat.ENTITY_TYPE_LENGTH_OFFSET, entityTypeBytes.length);
        buffer.put(PackedFileFormat.ENTITY_TYPE_OFFSET, entityTypeField);
        buffer.putInt(PackedFileFormat.IDENTIFIER_COUNT_OFFSET, identifierCount);
        buffer.putInt(PackedFileFormat.BUCKET_SIZE_OFFSET, bucketSize);
        buffer.putInt(PackedFileFormat.BUCKET_COUNT_OFFSET, bucketCount);
        buffer.putInt(PackedFileFormat.CHECKSUM_OFFSET, (int) checksum);
    }

    /**
     * Reads a header from the first {@link PackedFileFormat#HEADER_SIZE} bytes of {@code source}.
     * Uses absolute indexing; {@code source} is not consumed.
     *
     * @param source a buffer whose start is a packed file header
     * @return the parsed header
     * @throws CorruptDatasetException if the magic bytes are wrong or the entity-type field is
     *     malformed
     * @throws UnsupportedFormatVersionException if the format version is not the one this build reads
     */
    static Header readFrom(final ByteBuffer source) {
        final ByteBuffer buffer = source.duplicate().order(PackedFileFormat.BYTE_ORDER);

        final byte[] magic = new byte[PackedFileFormat.MAGIC_LENGTH];
        buffer.get(PackedFileFormat.MAGIC_OFFSET, magic);
        if (!Arrays.equals(magic, PackedFileFormat.magic())) {
            throw new CorruptDatasetException("bad magic bytes: " + Arrays.toString(magic));
        }

        final int formatVersion = buffer.getInt(PackedFileFormat.FORMAT_VERSION_OFFSET);
        if (formatVersion != PackedFileFormat.FORMAT_VERSION) {
            throw new UnsupportedFormatVersionException(formatVersion, PackedFileFormat.FORMAT_VERSION);
        }

        final int entityTypeLength = buffer.getInt(PackedFileFormat.ENTITY_TYPE_LENGTH_OFFSET);
        if (entityTypeLength < 1 || entityTypeLength > PackedFileFormat.MAX_ENTITY_TYPE_BYTES) {
            throw new CorruptDatasetException("entityType length out of range: " + entityTypeLength);
        }
        final byte[] entityTypeBytes = new byte[entityTypeLength];
        buffer.get(PackedFileFormat.ENTITY_TYPE_OFFSET, entityTypeBytes);
        for (final byte value : entityTypeBytes) {
            if (value < 0) {
                throw new CorruptDatasetException("entityType contains a non-ASCII byte");
            }
        }

        return new Header(
                formatVersion,
                new String(entityTypeBytes, StandardCharsets.US_ASCII),
                buffer.getInt(PackedFileFormat.IDENTIFIER_COUNT_OFFSET),
                buffer.getInt(PackedFileFormat.BUCKET_SIZE_OFFSET),
                buffer.getInt(PackedFileFormat.BUCKET_COUNT_OFFSET),
                Integer.toUnsignedLong(buffer.getInt(PackedFileFormat.CHECKSUM_OFFSET)));
    }
}
