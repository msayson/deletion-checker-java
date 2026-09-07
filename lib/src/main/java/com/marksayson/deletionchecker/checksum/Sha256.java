package com.marksayson.deletionchecker.checksum;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 digest, used for the dataset manifest's checksum.
 *
 * <p>The manifest is small and read once, so a cryptographic hash's per-byte cost does not matter,
 * and the manifest is the trust anchor pinning every entity-type file's expected CRC32C
 * (see {@link Crc32c}) — worth the stronger guarantee. It still provides corruption detection, not
 * artifact authenticity.
 */
public final class Sha256 {

    private Sha256() {
    }

    /**
     * Returns the SHA-256 digest of {@code data}.
     *
     * @param data the bytes to digest
     * @return the 32-byte digest
     */
    public static byte[] of(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException e) {
            // Every conforming Java platform provides SHA-256, so this is unreachable.
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
