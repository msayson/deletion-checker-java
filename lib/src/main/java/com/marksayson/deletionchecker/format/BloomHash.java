package com.marksayson.deletionchecker.format;

/**
 * The 64-bit hash the Bloom filter derives every block index and bit pattern from: FNV-1a
 * over the identifier's UTF-8 bytes, then the Murmur3 64-bit finalizer for avalanche so a
 * single-byte change to the input flips about half the output bits. Not cryptographic and not a
 * checksum — the dataset's threat model is accidental corruption only, and the Bloom filter
 * only ever needs a well-distributed hash of non-adversarial identifiers.
 */
final class BloomHash {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private BloomHash() {
    }

    static long hash64(final byte[] identifier) {
        long hash = FNV_OFFSET_BASIS;
        for (final byte b : identifier) {
            hash ^= b & 0xffL;
            hash *= FNV_PRIME;
        }
        // Murmur3 fmix64.
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return hash;
    }
}
