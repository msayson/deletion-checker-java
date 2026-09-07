package com.marksayson.deletionchecker.checksum;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * Checksum helpers that operate over a region of a buffer without copying it.
 */
public final class Checksums {

    private Checksums() {
    }

    /**
     * Computes the CRC32C of {@code file}'s bytes from index 0 to its {@code limit}, as if the
     * {@code fieldLength} bytes starting at {@code fieldOffset} were zero. This is how a file's own
     * checksum field is excluded from the value that will be written into it: the writer hashes with
     * the field zeroed, and verification re-derives the value the same way.
     *
     * <p>The three segments (before the field, {@code fieldLength} zero bytes, after the field) are
     * streamed in place; the file is never copied. {@code file}'s position and limit are unchanged.
     *
     * @param file a buffer whose {@code [0, limit)} range is the complete file
     * @param fieldOffset the index of the first byte to treat as zero
     * @param fieldLength the number of bytes to treat as zero
     * @return the CRC32C as an unsigned 32-bit value in the low bits of the result
     */
    public static long crc32cWithFieldZeroed(
            final ByteBuffer file, final int fieldOffset, final int fieldLength) {
        final int end = file.limit();
        final CRC32C crc = new CRC32C();
        crc.update(segment(file, 0, fieldOffset));
        crc.update(new byte[fieldLength]);
        crc.update(segment(file, fieldOffset + fieldLength, end));
        return crc.getValue();
    }

    private static ByteBuffer segment(final ByteBuffer file, final int from, final int to) {
        return file.duplicate().clear().limit(to).position(from);
    }
}
