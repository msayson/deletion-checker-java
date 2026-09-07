package com.marksayson.deletionchecker.format;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * A blocked ("split-block", as specified for Apache Parquet) Bloom filter over one entity type's
 * identifiers, checked before the two-level search on the lookup path. It answers only
 * "definitely not present" or "maybe present", so a "maybe" still falls through to the exact search
 * and the exactness guarantee is untouched; a "definitely not" is the common negative-lookup case
 * answered in one hash and one cache line.
 *
 * <p>The bit array is a run of {@code blockCount} blocks of {@value PackedFileFormat#BLOOM_BLOCK_BYTES}
 * bytes (256 bits = eight 32-bit words). {@link #hash64} picks a block; the low 32 bits of the hash
 * pick one bit in each of the eight words via the eight fixed salts. A lookup ORs its way through
 * one block's eight words — contiguous, so effectively a single cache-line read. Instances read the
 * mapped file by absolute index only and hold no mutable state, so they are safe for concurrent
 * callers; the array is never lifted onto the heap.
 */
final class BloomFilter {

    private static final int WORDS_PER_BLOCK = PackedFileFormat.BLOOM_BLOCK_BYTES / Integer.BYTES;

    /** One salt per word (Parquet split-block Bloom filter constants). */
    private static final int[] SALT = {
        0x47b6137b, 0x44974d91, 0x8824ad5b, 0xa2b7289d,
        0x705495c7, 0x2df1424b, 0x9efc4947, 0x5c6bfb31,
    };

    private final ByteBuffer data;
    private final int start;
    private final int blockCount;

    private BloomFilter(final ByteBuffer data, final int start, final int blockCount) {
        this.data = data;
        this.start = start;
        this.blockCount = blockCount;
    }

    /**
     * A query view over the filter section that starts at absolute index {@code start} in
     * {@code data}, or {@code null} when {@code blockCount} is zero (an empty dataset, or a v1 file
     * that predates the filter).
     */
    static BloomFilter view(final ByteBuffer data, final int start, final int blockCount) {
        return blockCount == 0 ? null : new BloomFilter(data, start, blockCount);
    }

    /**
     * Returns whether {@code identifier} might be in the set. {@code false} is exact — the identifier
     * is definitely absent. {@code true} means "maybe" and must be confirmed by the real search.
     */
    boolean mightContain(final byte[] identifier) {
        final long hash = BloomHash.hash64(identifier);
        final int blockBase = start + blockIndex(hash, blockCount) * PackedFileFormat.BLOOM_BLOCK_BYTES;
        final int lower = (int) hash;
        for (int i = 0; i < WORDS_PER_BLOCK; i++) {
            final int word = data.getInt(blockBase + i * Integer.BYTES);
            if ((word & bitMask(lower, i)) == 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the on-disk bytes of a filter over {@code identifiers}: {@code blockCount} blocks,
     * little-endian words, no header. An empty filter ({@code blockCount == 0}) is zero bytes.
     */
    static byte[] build(final List<byte[]> identifiers, final int blockCount) {
        if (blockCount == 0) {
            return new byte[0];
        }
        final int[] words = new int[blockCount * WORDS_PER_BLOCK];
        for (final byte[] identifier : identifiers) {
            final long hash = BloomHash.hash64(identifier);
            final int blockBase = blockIndex(hash, blockCount) * WORDS_PER_BLOCK;
            final int lower = (int) hash;
            for (int i = 0; i < WORDS_PER_BLOCK; i++) {
                words[blockBase + i] |= bitMask(lower, i);
            }
        }
        final ByteBuffer buffer =
                ByteBuffer.allocate(words.length * Integer.BYTES).order(PackedFileFormat.BYTE_ORDER);
        for (final int word : words) {
            buffer.putInt(word);
        }
        return buffer.array();
    }

    /**
     * The block count that gives roughly {@code targetFpr} for {@code identifierCount} identifiers
     * (Parquet split-block sizing: {@code bits = -8n / ln(1 - fpr^(1/8))}), or {@code 0} when there
     * are no identifiers or {@code targetFpr >= 1} (filter disabled).
     */
    static int blockCountFor(final int identifierCount, final double targetFpr) {
        if (identifierCount == 0 || targetFpr >= 1.0) {
            return 0;
        }
        final double bitsPerId = -8.0 / Math.log(1.0 - Math.pow(targetFpr, 0.125));
        final double blocks = Math.ceil(bitsPerId * identifierCount / (PackedFileFormat.BLOOM_BLOCK_BYTES * 8.0));
        return Math.max(1, (int) Math.min(blocks, Integer.MAX_VALUE));
    }

    private static int blockIndex(final long hash, final int blockCount) {
        // Lemire's fast reduction of the high 32 bits into [0, blockCount).
        return (int) (((hash >>> 32) * blockCount) >>> 32);
    }

    private static int bitMask(final int lower, final int word) {
        return 1 << ((lower * SALT[word]) >>> 27);
    }
}
