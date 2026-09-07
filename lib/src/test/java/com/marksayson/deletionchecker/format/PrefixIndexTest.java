package com.marksayson.deletionchecker.format;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrefixIndexTest {

    private static List<byte[]> bytes(final String... items) {
        final List<byte[]> list = new ArrayList<>(items.length);
        for (final String item : items) {
            list.add(item.getBytes(StandardCharsets.UTF_8));
        }
        return list;
    }

    private static int selectBucket(final PrefixIndex index, final String query) {
        return index.selectBucket(query.getBytes(StandardCharsets.UTF_8));
    }

    // --- Worked example: [A,B,C] [D,E,F] [G,H,I], separators A, D, G ---

    private static PrefixIndex section54Example() {
        return PrefixIndex.build(bytes("A", "B", "C", "D", "E", "F", "G", "H", "I"), 3);
    }

    @Test
    void section54ExampleHasTheExpectedOnDiskShape() {
        final PrefixIndex index = section54Example();
        assertEquals(9, index.identifierCount());
        assertEquals(3, index.bucketCount());
        assertArrayEquals(new int[] {0, 3, 6, 9}, index.startIndex());
        assertArrayEquals(new int[] {0, 1, 2, 3}, index.separatorOffset());
        assertArrayEquals("ADG".getBytes(StandardCharsets.UTF_8), index.separatorData());
    }

    @Test
    void selectsBucketByFloorOfTheSeparators() {
        final PrefixIndex index = section54Example();
        assertEquals(1, selectBucket(index, "F"), "between separators D and G");
        assertEquals(2, selectBucket(index, "G"), "equal to separator G");
        assertEquals(0, selectBucket(index, "A"), "equal to first separator");
        assertEquals(1, selectBucket(index, "D"), "equal to a middle separator");
        assertEquals(0, selectBucket(index, "@"), "before every separator");
        assertEquals(2, selectBucket(index, "Z"), "after every separator: last bucket");
    }

    @Test
    void selectedBucketRangeIsTheExpectedSlice() {
        final PrefixIndex index = section54Example();
        final int bucket = selectBucket(index, "E");
        assertEquals(1, bucket);
        assertEquals(3, index.bucketStart(bucket));
        assertEquals(6, index.bucketEnd(bucket));
    }

    // --- boundary sizes ---

    @Test
    void emptyListYieldsOneBucketWithAnEmptySeparator() {
        final PrefixIndex index = PrefixIndex.build(bytes(), 128);
        assertEquals(0, index.identifierCount());
        assertEquals(1, index.bucketCount());
        assertArrayEquals(new int[] {0, 0}, index.startIndex());
        assertArrayEquals(new int[] {0, 0}, index.separatorOffset());
        assertArrayEquals(new byte[0], index.separatorData());
        assertEquals(0, selectBucket(index, "anything"));
        assertEquals(0, index.bucketStart(0));
        assertEquals(0, index.bucketEnd(0));
    }

    @Test
    void singleIdentifierYieldsOneBucket() {
        final PrefixIndex index = PrefixIndex.build(bytes("m"), 128);
        assertEquals(1, index.bucketCount());
        assertArrayEquals(new int[] {0, 1}, index.startIndex());
        assertArrayEquals("m".getBytes(StandardCharsets.UTF_8), index.separatorData());
        assertEquals(0, selectBucket(index, "a"));
        assertEquals(0, selectBucket(index, "m"));
        assertEquals(0, selectBucket(index, "z"));
    }

    @Test
    void countBelowBucketSizeYieldsOneBucket() {
        assertEquals(1, PrefixIndex.build(bytes("a", "b", "c", "d", "e"), 128).bucketCount());
    }

    @Test
    void singleArgBuildUsesTheDefaultBucketSize() {
        final List<byte[]> identifiers = sequential(2 * PrefixIndex.DEFAULT_BUCKET_SIZE);
        final PrefixIndex index = PrefixIndex.build(identifiers);
        assertEquals(2, index.bucketCount());
        assertArrayEquals(
                PrefixIndex.build(identifiers, PrefixIndex.DEFAULT_BUCKET_SIZE).startIndex(),
                index.startIndex());
    }

    @Test
    void countEqualToBucketSizeYieldsOneBucket() {
        final PrefixIndex index = PrefixIndex.build(sequential(128), 128);
        assertEquals(1, index.bucketCount());
    }

    @Test
    void countOneOverBucketSizeYieldsTwoBuckets() {
        final PrefixIndex index = PrefixIndex.build(sequential(129), 128);
        assertEquals(2, index.bucketCount());
        assertArrayEquals(new int[] {0, 128, 129}, index.startIndex());
    }

    @Test
    void everyBucketHoldsAboutBucketSizeEntries() {
        final int count = 1000;
        final int bucketSize = 128;
        final PrefixIndex index = PrefixIndex.build(sequential(count), bucketSize);

        assertEquals(8, index.bucketCount());
        int total = 0;
        for (int bucket = 0; bucket < index.bucketCount(); bucket++) {
            final int size = index.bucketEnd(bucket) - index.bucketStart(bucket);
            total += size;
            assertTrue(size >= 1 && size <= bucketSize, "bucket " + bucket + " size " + size);
        }
        assertEquals(count, total);
        assertEquals(count, index.bucketEnd(index.bucketCount() - 1));
    }

    // --- comparison is unsigned byte-wise, not UTF-16 order ---

    @Test
    void separatorComparisonUsesUnsignedByteOrder() {
        // Sorted by UTF-8 bytes: "A" (0x41), U+FF21 (EF BC A1), U+10000 (F0 90 80 80).
        final PrefixIndex index = PrefixIndex.build(bytes("A", "Ａ", "𐀀"), 1);
        assertEquals(3, index.bucketCount());

        assertEquals(0, selectBucket(index, "A"));
        assertEquals(1, selectBucket(index, "Ａ"));
        assertEquals(2, selectBucket(index, "𐀀"));

        // A key whose first byte is 0xF0 but which sorts before the supplementary separator.
        assertEquals(1, index.selectBucket(new byte[] {(byte) 0xF0, 0x00}));
    }

    // --- argument validation ---

    @Test
    void rejectsNonPositiveBucketSize() {
        assertThrows(IllegalArgumentException.class, () -> PrefixIndex.build(bytes("a"), 0));
        assertThrows(IllegalArgumentException.class, () -> PrefixIndex.build(bytes("a"), -1));
    }

    // --- accessors return defensive copies ---

    @Test
    void accessorsReturnCopies() {
        final PrefixIndex index = section54Example();
        index.startIndex()[0] = 99;
        index.separatorOffset()[0] = 99;
        index.separatorData()[0] = 99;
        assertArrayEquals(new int[] {0, 3, 6, 9}, index.startIndex());
        assertArrayEquals(new int[] {0, 1, 2, 3}, index.separatorOffset());
        assertArrayEquals("ADG".getBytes(StandardCharsets.UTF_8), index.separatorData());
    }

    /** {@code count} identifiers that are already in unsigned-byte sorted order and unique. */
    private static List<byte[]> sequential(final int count) {
        final List<byte[]> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(String.format("%08d", i).getBytes(StandardCharsets.US_ASCII));
        }
        return list;
    }
}
