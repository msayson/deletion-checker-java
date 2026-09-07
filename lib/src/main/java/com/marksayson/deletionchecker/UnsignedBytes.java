package com.marksayson.deletionchecker;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Lexicographic comparison of byte sequences with each byte treated as unsigned (0..255).
 *
 * <p>This is the order the dataset is sorted in and searched by. It matches UTF-8 byte order, which
 * in turn matches Unicode code-point order. Java's signed {@code byte} comparison and
 * {@link String#compareTo} (UTF-16 code-unit order) both disagree with it for code points above
 * U+FFFF, so all format code compares through here rather than by either of those.
 */
public final class UnsignedBytes {

    private UnsignedBytes() {
    }

    /**
     * Compares two byte arrays.
     *
     * @param a the first array
     * @param b the second array
     * @return a negative, zero, or positive value as {@code a} is lexicographically less than, equal
     *     to, or greater than {@code b}
     */
    public static int lexicographicalCompare(final byte[] a, final byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    /**
     * Compares a range of one byte array against a range of another.
     *
     * @param a the first array
     * @param aFrom the first index of {@code a} to compare, inclusive
     * @param aTo the index after the last index of {@code a} to compare, exclusive
     * @param b the second array
     * @param bFrom the first index of {@code b} to compare, inclusive
     * @param bTo the index after the last index of {@code b} to compare, exclusive
     * @return a negative, zero, or positive value as the {@code a} range is lexicographically less
     *     than, equal to, or greater than the {@code b} range
     */
    public static int lexicographicalCompare(
            final byte[] a, final int aFrom, final int aTo,
            final byte[] b, final int bFrom, final int bTo) {
        return Arrays.compareUnsigned(a, aFrom, aTo, b, bFrom, bTo);
    }

    /**
     * Compares a range of a byte array against a range of a {@link ByteBuffer}, reading the buffer by
     * absolute index without disturbing its position or limit.
     *
     * @param a the array
     * @param aFrom the first index of {@code a} to compare, inclusive
     * @param aTo the index after the last index of {@code a} to compare, exclusive
     * @param b the buffer
     * @param bFrom the first absolute index of {@code b} to compare, inclusive
     * @param bTo the absolute index after the last index of {@code b} to compare, exclusive
     * @return a negative, zero, or positive value as the {@code a} range is lexicographically less
     *     than, equal to, or greater than the {@code b} range
     */
    public static int lexicographicalCompare(
            final byte[] a, final int aFrom, final int aTo,
            final ByteBuffer b, final int bFrom, final int bTo) {
        final int aLength = aTo - aFrom;
        final int bLength = bTo - bFrom;
        final int shared = Math.min(aLength, bLength);
        for (int i = 0; i < shared; i++) {
            final int aByte = a[aFrom + i] & 0xFF;
            final int bByte = b.get(bFrom + i) & 0xFF;
            if (aByte != bByte) {
                return aByte - bByte;
            }
        }
        return aLength - bLength;
    }
}
