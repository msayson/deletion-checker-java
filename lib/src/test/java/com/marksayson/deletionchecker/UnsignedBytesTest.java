package com.marksayson.deletionchecker;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsignedBytesTest {

    @Test
    void equalArraysCompareZero() {
        assertEquals(0, UnsignedBytes.lexicographicalCompare(
                new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
    }

    @Test
    void arrayPrefixIsLessThanLongerArray() {
        assertTrue(UnsignedBytes.lexicographicalCompare(
                new byte[] {1, 2}, new byte[] {1, 2, 0}) < 0);
    }

    @Test
    void wholeArrayComparisonTreatsBytesAsUnsigned() {
        // 0x80 (128) must sort after 0x7F (127); a signed comparison would flip this.
        assertTrue(UnsignedBytes.lexicographicalCompare(
                new byte[] {(byte) 0x80}, new byte[] {0x7F}) > 0);
    }

    @Test
    void rangeComparisonUsesOnlyTheSelectedBytes() {
        final byte[] a = {9, 9, 1, 2, 3, 9};
        final byte[] b = {0, 1, 2, 3, 0, 0};
        assertEquals(0, UnsignedBytes.lexicographicalCompare(a, 2, 5, b, 1, 4));
    }

    @Test
    void shorterRangeThatIsAPrefixIsLess() {
        final byte[] a = {1, 2};
        final byte[] b = {1, 2, 3};
        assertTrue(UnsignedBytes.lexicographicalCompare(a, 0, 2, b, 0, 3) < 0);
    }

    @Test
    void bufferEqualRangeComparesZero() {
        final byte[] a = {1, 2, 3};
        final ByteBuffer b = ByteBuffer.wrap(new byte[] {9, 1, 2, 3, 9});
        assertEquals(0, UnsignedBytes.lexicographicalCompare(a, 0, 3, b, 1, 4));
    }

    @Test
    void bufferComparisonTreatsBytesAsUnsigned() {
        final byte[] a = {(byte) 0x80};
        final ByteBuffer b = ByteBuffer.wrap(new byte[] {0x7F});
        assertTrue(UnsignedBytes.lexicographicalCompare(a, 0, 1, b, 0, 1) > 0);
    }

    @Test
    void arrayThatIsPrefixOfBufferIsLess() {
        final byte[] a = {1, 2};
        final ByteBuffer b = ByteBuffer.wrap(new byte[] {1, 2, 3});
        assertTrue(UnsignedBytes.lexicographicalCompare(a, 0, 2, b, 0, 3) < 0);
    }

    @Test
    void bufferThatIsPrefixOfArrayIsGreater() {
        final byte[] a = {1, 2, 3};
        final ByteBuffer b = ByteBuffer.wrap(new byte[] {1, 2});
        assertTrue(UnsignedBytes.lexicographicalCompare(a, 0, 3, b, 0, 2) > 0);
    }

    @Test
    void bufferPositionAndLimitAreUntouched() {
        final byte[] a = {1, 2, 3};
        final ByteBuffer b = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 0});
        final int position = b.position();
        final int limit = b.limit();
        UnsignedBytes.lexicographicalCompare(a, 0, 3, b, 1, 4);
        assertEquals(position, b.position());
        assertEquals(limit, b.limit());
    }

    @Test
    void matchesUtf8ByteOrderWhereStringCompareToDisagrees() {
        // U+FF21 (FULLWIDTH LATIN A) vs U+10000. UTF-16 code-unit order and UTF-8 byte order
        // give opposite results.
        final String fullwidthA = "Ａ";
        final String supplementary = "𐀀";

        assertTrue(fullwidthA.compareTo(supplementary) > 0);
        assertTrue(UnsignedBytes.lexicographicalCompare(
                fullwidthA.getBytes(StandardCharsets.UTF_8),
                supplementary.getBytes(StandardCharsets.UTF_8)) < 0);
    }

    @Test
    void agreesWithStringCompareToForAscii() {
        final byte[] apple = "apple".getBytes(StandardCharsets.UTF_8);
        final byte[] banana = "banana".getBytes(StandardCharsets.UTF_8);
        assertTrue(UnsignedBytes.lexicographicalCompare(apple, banana) < 0);
    }
}
