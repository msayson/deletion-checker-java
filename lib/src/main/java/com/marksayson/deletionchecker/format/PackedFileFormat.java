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
 *        4     4   formatVersion
 *        8     4   entityTypeLength   (1..64)
 *       12    64   entityType         (ASCII, zero-padded)
 *       76     4   identifierCount
 *       80     4   bucketSize         (K)
 *       84     4   bucketCount
 *       88     4   checksum           (CRC32C, this field zeroed while hashing)
 *       92         end of header
 * </pre>
 */
final class PackedFileFormat {

    /** Byte order of every multi-byte integer in the file. */
    static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    /** Binary layout version this build reads and writes. */
    static final int FORMAT_VERSION = 1;

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

    /** Total size of the fixed header, in bytes. */
    static final int HEADER_SIZE = 92;

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
}
