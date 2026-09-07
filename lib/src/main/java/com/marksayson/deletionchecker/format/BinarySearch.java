package com.marksayson.deletionchecker.format;

import com.marksayson.deletionchecker.UnsignedBytes;
import java.nio.ByteBuffer;

/**
 * Binary search for an exact identifier match within a contiguous range of entries, reading the
 * identifiers straight from a buffer with no intermediate allocation.
 *
 * <p>The JDK {@code Arrays}/{@code Collections.binarySearch} helpers don't fit: the entries are
 * variable-length UTF-8 slices addressed via an offset table, not a {@code T[]} or {@code List}.
 * Adapting them needs a boxed {@code List<Integer>} index view plus a per-probe slice copy, which
 * regresses on both allocation and speed over this implementation.
 */
final class BinarySearch {

    private BinarySearch() {
    }

    /**
     * Returns whether {@code query} exactly equals one of the identifiers in entry range
     * {@code [lo, hi)}.
     *
     * <p>Identifier {@code i} occupies
     * {@code [dataStart + offsetOf(i), dataStart + offsetOf(i + 1))} in {@code buffer}, where
     * {@code offsetOf(i)} is the little-endian {@code int} at {@code offsetTableStart + i * 4}.
     * {@code buffer} must be little-endian ordered and is read by absolute index only, so concurrent
     * searches are safe.
     *
     * @param query the identifier to look for, UTF-8 encoded
     * @param buffer the file buffer
     * @param offsetTableStart absolute index of the identifier offset table
     * @param dataStart absolute index of the identifier data block
     * @param lo first entry index to consider, inclusive
     * @param hi last entry index to consider, exclusive
     * @return whether {@code query} is present in {@code [lo, hi)}
     */
    static boolean contains(
            final byte[] query,
            final ByteBuffer buffer,
            final int offsetTableStart,
            final int dataStart,
            final int lo,
            final int hi) {
        int low = lo;
        int high = hi;
        while (low < high) {
            final int mid = (low + high) >>> 1;
            final int start = dataStart + buffer.getInt(offsetTableStart + mid * Integer.BYTES);
            final int end = dataStart + buffer.getInt(offsetTableStart + (mid + 1) * Integer.BYTES);
            final int comparison = UnsignedBytes.lexicographicalCompare(
                    query, 0, query.length, buffer, start, end);
            if (comparison == 0) {
                return true;
            }
            if (comparison < 0) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return false;
    }
}
