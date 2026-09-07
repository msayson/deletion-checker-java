package com.marksayson.deletionchecker.format;

import com.marksayson.deletionchecker.UnsignedBytes;
import java.util.List;

/**
 * Adaptive, equal-count bucket index over a sorted identifier list.
 *
 * <p>The sorted identifiers are sliced into buckets of a target size {@code K}; each bucket records
 * its first entry index and its first identifier as a <em>separator</em>. A lookup first finds the
 * candidate bucket with a floor search over the small, cache-resident separator array, then binary
 * searches only that bucket's entry range. All comparisons are unsigned byte-wise
 * ({@link UnsignedBytes}), matching the order the dataset is sorted in.
 *
 * <p>The index is built in the exact shape it is serialized: a {@code startIndex} table and a
 * {@code separatorOffset} table of {@code bucketCount + 1} entries each, plus one contiguous
 * {@code separatorData} block. An empty identifier list yields a single bucket with an empty
 * separator, so build, serialization, and search stay uniform — the zero-identifier short-circuit
 * is then only an optimization, not a correctness requirement.
 *
 * <p>Callers must pass identifiers already sorted by unsigned bytes and deduplicated; this class
 * does not verify either.
 */
public final class PrefixIndex {

    /**
     * The generator's launch default for the bucket target size {@code K}. The runtime
     * reads the actual {@code K} of a dataset from its file header, never from this constant.
     */
    public static final int DEFAULT_BUCKET_SIZE = 128;

    private final int identifierCount;
    private final int bucketCount;
    private final int[] startIndex;
    private final int[] separatorOffset;
    private final byte[] separatorData;

    private PrefixIndex(
            final int identifierCount,
            final int bucketCount,
            final int[] startIndex,
            final int[] separatorOffset,
            final byte[] separatorData) {
        this.identifierCount = identifierCount;
        this.bucketCount = bucketCount;
        this.startIndex = startIndex;
        this.separatorOffset = separatorOffset;
        this.separatorData = separatorData;
    }

    /**
     * Builds an index over {@code sortedIdentifiers} using {@link #DEFAULT_BUCKET_SIZE}.
     *
     * @param sortedIdentifiers the identifiers, already sorted by unsigned bytes and deduplicated
     * @return the index
     */
    public static PrefixIndex build(final List<byte[]> sortedIdentifiers) {
        return build(sortedIdentifiers, DEFAULT_BUCKET_SIZE);
    }

    /**
     * Builds an index over {@code sortedIdentifiers} with the given bucket target size.
     *
     * @param sortedIdentifiers the identifiers, already sorted by unsigned bytes and deduplicated
     * @param bucketSize the target number of entries per bucket ({@code K}); must be at least 1
     * @return the index
     * @throws IllegalArgumentException if {@code bucketSize} is less than 1
     */
    public static PrefixIndex build(final List<byte[]> sortedIdentifiers, final int bucketSize) {
        if (bucketSize < 1) {
            throw new IllegalArgumentException("bucketSize must be at least 1: " + bucketSize);
        }
        final int count = sortedIdentifiers.size();
        final int buckets = Math.max(1, Math.ceilDiv(count, bucketSize));

        final int[] starts = new int[buckets + 1];
        final int[] offsets = new int[buckets + 1];
        int separatorLength = 0;
        for (int bucket = 0; bucket < buckets; bucket++) {
            final int start = Math.min(bucket * bucketSize, count);
            starts[bucket] = start;
            offsets[bucket] = separatorLength;
            if (start < count) {
                separatorLength += sortedIdentifiers.get(start).length;
            }
        }
        starts[buckets] = count;
        offsets[buckets] = separatorLength;

        final byte[] separators = new byte[separatorLength];
        for (int bucket = 0; bucket < buckets; bucket++) {
            final int start = starts[bucket];
            if (start < count) {
                final byte[] separator = sortedIdentifiers.get(start);
                System.arraycopy(separator, 0, separators, offsets[bucket], separator.length);
            }
        }
        return new PrefixIndex(count, buckets, starts, offsets, separators);
    }

    /**
     * Returns the number of identifiers indexed.
     *
     * @return the identifier count
     */
    public int identifierCount() {
        return identifierCount;
    }

    /**
     * Returns the number of buckets, always at least 1.
     *
     * @return the bucket count
     */
    public int bucketCount() {
        return bucketCount;
    }

    /**
     * Selects the candidate bucket for {@code query}: the greatest bucket index whose separator is
     * {@code <= query} by unsigned byte order, or 0 when {@code query} precedes every separator.
     * This is a floor search, not an exact match.
     *
     * @param query the lookup key, UTF-8 encoded
     * @return a bucket index in {@code [0, bucketCount)}
     */
    public int selectBucket(final byte[] query) {
        int lo = 0;
        int hi = bucketCount - 1;
        int selected = 0;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (compareSeparatorTo(mid, query) <= 0) {
                selected = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return selected;
    }

    /**
     * Returns the first entry index of the given bucket.
     *
     * @param bucket a bucket index in {@code [0, bucketCount)}
     * @return the entry index where {@code bucket} begins
     */
    public int bucketStart(final int bucket) {
        return startIndex[bucket];
    }

    /**
     * Returns the entry index just past the last entry of the given bucket.
     *
     * @param bucket a bucket index in {@code [0, bucketCount)}
     * @return the entry index where {@code bucket} ends, exclusive
     */
    public int bucketEnd(final int bucket) {
        return startIndex[bucket + 1];
    }

    /**
     * Returns a copy of the {@code startIndex} table: {@code bucketCount + 1} entries, the last equal
     * to {@link #identifierCount()}.
     *
     * @return the start-index table
     */
    public int[] startIndex() {
        return startIndex.clone();
    }

    /**
     * Returns a copy of the {@code separatorOffset} table: {@code bucketCount + 1} byte offsets into
     * {@link #separatorData()}, one per separator plus a trailing total length.
     *
     * @return the separator-offset table
     */
    public int[] separatorOffset() {
        return separatorOffset.clone();
    }

    /**
     * Returns a copy of the contiguous {@code separatorData} block: every bucket's separator bytes
     * stored back to back in bucket order.
     *
     * @return the separator-data block
     */
    public byte[] separatorData() {
        return separatorData.clone();
    }

    private int compareSeparatorTo(final int bucket, final byte[] query) {
        return UnsignedBytes.lexicographicalCompare(
                separatorData, separatorOffset[bucket], separatorOffset[bucket + 1],
                query, 0, query.length);
    }
}
