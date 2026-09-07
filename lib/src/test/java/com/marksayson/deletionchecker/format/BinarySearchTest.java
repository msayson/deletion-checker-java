package com.marksayson.deletionchecker.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySearchTest {

    /** Lays {@code identifiers} out as an offset table followed by the data, then searches it. */
    private static boolean search(final List<byte[]> identifiers, final byte[] query) {
        final int offsetTableStart = 0;
        final int dataStart = Integer.BYTES * (identifiers.size() + 1);
        int dataLength = 0;
        for (final byte[] identifier : identifiers) {
            dataLength += identifier.length;
        }
        final ByteBuffer buffer =
                ByteBuffer.allocate(dataStart + dataLength).order(PackedFileFormat.BYTE_ORDER);
        int offset = 0;
        int index = 0;
        for (final byte[] identifier : identifiers) {
            buffer.putInt(offsetTableStart + index * Integer.BYTES, offset);
            buffer.put(dataStart + offset, identifier);
            offset += identifier.length;
            index++;
        }
        buffer.putInt(offsetTableStart + index * Integer.BYTES, offset);

        return BinarySearch.contains(
                query, buffer, offsetTableStart, dataStart, 0, identifiers.size());
    }

    private static boolean contains(final List<String> identifiers, final String query) {
        final List<byte[]> bytes = new ArrayList<>(identifiers.size());
        for (final String identifier : identifiers) {
            bytes.add(identifier.getBytes(StandardCharsets.UTF_8));
        }
        return search(bytes, query.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] b(final int... values) {
        final byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    @Test
    void findsAnIdentifierAtEveryPosition() {
        final List<String> identifiers = List.of("apple", "banana", "cherry", "date", "fig");
        for (final String identifier : identifiers) {
            assertTrue(contains(identifiers, identifier), identifier);
        }
    }

    @Test
    void reportsMissesBeforeBetweenAndAfterTheEntries() {
        final List<String> identifiers = List.of("banana", "cherry", "fig");
        assertFalse(contains(identifiers, "apple"));
        assertFalse(contains(identifiers, "date"));
        assertFalse(contains(identifiers, "grape"));
    }

    @Test
    void distinguishesAPrefixFromAFullMatch() {
        final List<String> identifiers = List.of("cat", "caterpillar");
        assertTrue(contains(identifiers, "cat"));
        assertTrue(contains(identifiers, "caterpillar"));
        assertFalse(contains(identifiers, "cate"), "sorts between the two entries");
        assertFalse(contains(identifiers, "catx"), "sorts after both entries");
        assertFalse(contains(identifiers, "ca"), "sorts before both entries");
    }

    @Test
    void emptyRangeContainsNothing() {
        final List<String> identifiers = List.of("a", "b", "c");
        // Search the empty range [1, 1).
        final ByteBuffer buffer = ByteBuffer.allocate(64).order(PackedFileFormat.BYTE_ORDER);
        assertFalse(BinarySearch.contains("b".getBytes(StandardCharsets.UTF_8), buffer, 0, 32, 1, 1));
        // whole range still finds it
        assertTrue(contains(identifiers, "b"));
    }

    @Test
    void singleEntryRange() {
        final List<String> identifiers = List.of("only");
        assertTrue(contains(identifiers, "only"));
        assertFalse(contains(identifiers, "aaaa"), "sorts before the single entry");
        assertFalse(contains(identifiers, "zzzz"), "sorts after the single entry");
    }

    /**
     * Navigation must use unsigned byte order. Under a signed comparison, {@code 0x80..0xFF} would
     * sort <em>below</em> {@code 0x00}, so the search would steer the wrong way and miss entries
     * that are present.
     */
    @Test
    void navigatesByUnsignedByteOrder() {
        final List<byte[]> identifiers = List.of(
                b(0x00), b(0x01), b(0x10), b(0x7F), b(0x80), b(0xC0), b(0xFF));

        for (final byte[] identifier : identifiers) {
            assertTrue(search(identifiers, identifier));
        }
        // These reach their entry only via comparisons against 0x7F / high bytes that a signed
        // comparison gets backwards.
        assertTrue(search(identifiers, b(0x80)));
        assertTrue(search(identifiers, b(0xFF)));
        assertTrue(search(identifiers, b(0xC0)));

        assertFalse(search(identifiers, b(0x08)));
        assertFalse(search(identifiers, b(0x40)));
        assertFalse(search(identifiers, b(0x90)));
        assertFalse(search(identifiers, b(0xE0)));
    }

    @Test
    void navigatesManyStepsInBothDirections() {
        final List<String> identifiers = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            identifiers.add(String.format("%03d", i * 3)); // 000, 003, ... 093
        }

        for (final String identifier : identifiers) {
            assertTrue(contains(identifiers, identifier), identifier);
        }
        for (int i = 0; i < 31; i++) {
            final String between = String.format("%03d", i * 3 + 1); // 001, 004, ... interior misses
            assertFalse(contains(identifiers, between), between);
        }
        assertFalse(contains(identifiers, "00"), "shorter, sorts before the first entry");
        assertFalse(contains(identifiers, "999"), "sorts after the last entry");
    }
}
