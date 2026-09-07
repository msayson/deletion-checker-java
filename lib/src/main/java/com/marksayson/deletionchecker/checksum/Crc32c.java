package com.marksayson.deletionchecker.checksum;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * CRC32C (Castagnoli, 32-bit) — the integrity check on each packed entity-type file.
 *
 * <p>Delegates to {@link CRC32C}, which the JVM accelerates with the CPU's CRC instruction where
 * available. CRC32C detects accidental corruption and truncation (bit flips, burst errors); it is
 * not a defense against a deliberately crafted collision, which is outside the dataset's threat
 * model. The manifest's SHA-256 (see {@link Sha256}) is the trust anchor above it.
 */
public final class Crc32c {

    private Crc32c() {
    }

    /**
     * Returns the CRC32C of {@code data}.
     *
     * @param data the bytes to checksum
     * @return the CRC32C as an unsigned 32-bit value (0 to 2^32 - 1) in the low bits of the result
     */
    public static long of(final byte[] data) {
        final CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Returns the CRC32C of {@code data}'s remaining bytes, leaving its position and limit unchanged.
     *
     * @param data the bytes to checksum, from {@code position} to {@code limit}
     * @return the CRC32C as an unsigned 32-bit value (0 to 2^32 - 1) in the low bits of the result
     */
    public static long of(final ByteBuffer data) {
        final CRC32C crc = new CRC32C();
        crc.update(data.duplicate());
        return crc.getValue();
    }
}
