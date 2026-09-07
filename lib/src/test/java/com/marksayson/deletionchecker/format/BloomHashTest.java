package com.marksayson.deletionchecker.format;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomHashTest {

    private static long hash(final String value) {
        return BloomHash.hash64(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void isDeterministic() {
        assertEquals(hash("customer-a1b2c3"), hash("customer-a1b2c3"));
    }

    @Test
    void emptyInputHashesToTheFinalizedBasis() {
        // FNV-1a offset basis with no bytes mixed in, run through Murmur3 fmix64.
        assertEquals(0xefd01f60ba992926L, BloomHash.hash64(new byte[0]));
    }

    @Test
    void aOneByteChangeAvalanchesAboutHalfTheBits() {
        final long a = hash("customer-a1b2c3");
        final long b = hash("customer-a1b2c4");
        assertNotEquals(a, b);
        final int flipped = Long.bitCount(a ^ b);
        assertTrue(flipped >= 20 && flipped <= 44, "flipped " + flipped + " of 64 bits");
    }

    @Test
    void bothHalvesAreWellDistributed() {
        // The Bloom filter uses the high 32 bits for the block index and the low 32 for the bit
        // pattern, so both halves must spread evenly across 16 buckets.
        final int samples = 200_000;
        final int[] highBuckets = new int[16];
        final int[] lowBuckets = new int[16];
        final SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < samples; i++) {
            final long h = BloomHash.hash64(Long.toHexString(random.nextLong())
                    .getBytes(StandardCharsets.US_ASCII));
            highBuckets[(int) ((h >>> 32) & 0xF)]++;
            lowBuckets[(int) (h & 0xF)]++;
        }
        final int expected = samples / 16;
        for (int i = 0; i < 16; i++) {
            assertTrue(Math.abs(highBuckets[i] - expected) < expected / 5,
                    "high bucket " + i + " = " + highBuckets[i]);
            assertTrue(Math.abs(lowBuckets[i] - expected) < expected / 5,
                    "low bucket " + i + " = " + lowBuckets[i]);
        }
    }
}
