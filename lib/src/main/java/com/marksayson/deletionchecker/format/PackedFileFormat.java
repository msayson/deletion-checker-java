package com.marksayson.deletionchecker.format;

import java.nio.ByteOrder;

/**
 * Constants defining the packed entity-type file layout: the magic bytes, the binary layout version
 * this build reads and writes, and the byte offset of every fixed header field.
 *
 * <p>All multi-byte integers in the file are little-endian, a fixed convention independent of the
 * host's native order.
 *
 * <pre>
 *   offset  size  field
 *        0     4   magic
 *        4     4   formatVersion         (1 or 2 — this build writes 2, reads both)
 *        8     4   entityTypeLength      (1..64)
 *       12    64   entityType            (ASCII, zero-padded)
 *       76     4   identifierCount
 *       80     4   bucketSize            (K)
 *       84     4   bucketCount
 *       88     4   checksum              (CRC32C, this field zeroed while hashing)
 *       92     4   bloomBlockCount       (v2 only; 0 == no Bloom filter)
 *       96         end of header         (v1 header ends at 92)
 * </pre>
 *
 * <p>Sections follow the header in order: Prefix Index, Bloom Filter ({@code bloomBlockCount}
 * blocks of {@value #BLOOM_BLOCK_BYTES} bytes; absent in v1 and for an empty dataset), Identifier
 * Offset Table, Identifier Data.
 */
final class PackedFileFormat {

    /** Byte order of every multi-byte integer in the file. */
    static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** Binary layout version this build writes. */
    static final int FORMAT_VERSION = 2;

    /** Oldest binary layout version this build can still read. */
    static final int MIN_READ_FORMAT_VERSION = 1;

    /** Maximum length, in ASCII bytes, of the {@code entityType} header field. */
    static final int MAX_ENTITY_TYPE_BYTES = 64;

    static final int MAGIC_OFFSET = 0;
    static final int MAGIC_LENGTH = 4;
    static final int FORMAT_VERSION_OFFSET = 4;
    static final int ENTITY_TYPE_LENGTH_OFFSET = 8;
    static final int ENTITY_TYPE_OFFSET = 12;
    static final int IDENTIFIER_COUNT_OFFSET = 76;
    static final int BUCKET_SIZE_OFFSET = 80;
    static final int BUCKET_COUNT_OFFSET = 84;
    static final int CHECKSUM_OFFSET = 88;
    static final int CHECKSUM_LENGTH = 4;
    static final int BLOOM_BLOCK_COUNT_OFFSET = 92;

    /** Fixed header size of a v1 file. */
    static final int HEADER_SIZE_V1 = 92;

    /** Fixed header size of a v2 file, and the size this build writes. */
    static final int HEADER_SIZE = 96;

    /** Bytes per blocked-Bloom-filter block: 256 bits, eight 32-bit words (Parquet split-block). */
    static final int BLOOM_BLOCK_BYTES = 32;

    /** High-bit byte to catch 7-bit-stripping transfers, then {@code "DCS"}. */
    private static final byte[] MAGIC = {(byte) 0x89, 'D', 'C', 'S'};

    private PackedFileFormat() {
    }

    /**
     * Returns a fresh copy of the magic bytes that begin every packed file.
     *
     * @return the {@value #MAGIC_LENGTH} magic bytes
     */
    static byte[] magic() {
        return MAGIC.clone();
    }

    /**
     * Returns the fixed header size for a file of {@code formatVersion}.
     *
     * @param formatVersion 1 or 2
     * @return the header size in bytes
     */
    static int headerSize(final int formatVersion) {
        return formatVersion >= 2 ? HEADER_SIZE : HEADER_SIZE_V1;
    }
}
