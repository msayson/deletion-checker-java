package com.marksayson.deletionchecker.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    private static final IntFunction<String> UUID_LIKE = i ->
            java.util.UUID.nameUUIDFromBytes(Integer.toString(i).getBytes(StandardCharsets.US_ASCII))
                    .toString();
    private static final IntFunction<String> HEX = i -> Long.toHexString(0x9E3779B97F4A7C15L * i);
    private static final IntFunction<String> CUSTOMER = i -> "customer-" + Integer.toHexString(i);

    private static List<byte[]> ids(final IntFunction<String> shape, final int from, final int to) {
        final List<byte[]> list = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            list.add(shape.apply(i).getBytes(StandardCharsets.UTF_8));
        }
        return list;
    }

    private static BloomFilter of(final List<byte[]> members, final int blockCount) {
        final byte[] bytes = BloomFilter.build(members, blockCount);
        return BloomFilter.view(ByteBuffer.wrap(bytes).order(PackedFileFormat.BYTE_ORDER), 0, blockCount);
    }

    @Test
    void hasNoFalseNegatives() {
        for (final IntFunction<String> shape : List.of(UUID_LIKE, HEX, CUSTOMER)) {
            final List<byte[]> members = ids(shape, 0, 50_000);
            final BloomFilter filter =
                    of(members, BloomFilter.blockCountFor(members.size(), 0.01));
            for (final byte[] member : members) {
                assertTrue(filter.mightContain(member));
            }
        }
    }

    @Test
    void measuredFalsePositiveRateIsNearTheTarget() {
        final double target = 0.01;
        for (final IntFunction<String> shape : List.of(UUID_LIKE, HEX, CUSTOMER)) {
            final List<byte[]> members = ids(shape, 0, 100_000);
            final Set<String> memberSet = new HashSet<>();
            members.forEach(m -> memberSet.add(new String(m, StandardCharsets.UTF_8)));
            final BloomFilter filter =
                    of(members, BloomFilter.blockCountFor(members.size(), target));

            int falsePositives = 0;
            int probes = 0;
            for (int i = 100_000; probes < 100_000; i++) {
                final String candidate = shape.apply(i);
                if (memberSet.contains(candidate)) {
                    continue;
                }
                probes++;
                if (filter.mightContain(candidate.getBytes(StandardCharsets.UTF_8))) {
                    falsePositives++;
                }
            }
            final double rate = (double) falsePositives / probes;
            assertTrue(rate <= target * 3, "measured FPR " + rate + " for shape probe");
        }
    }

    @Test
    void hasNoFalseNegativesAtAnyBlockCountAndByteRoundTrips() {
        final List<byte[]> members = ids(HEX, 0, 20_000);
        for (final int blockCount : new int[] {1, 8, 64, 512, 4096}) {
            final byte[] bytes = BloomFilter.build(members, blockCount);
            assertEquals(blockCount * PackedFileFormat.BLOOM_BLOCK_BYTES, bytes.length);

            final BloomFilter filter =
                    BloomFilter.view(ByteBuffer.wrap(bytes).order(PackedFileFormat.BYTE_ORDER),
                            0, blockCount);
            for (final byte[] member : members) {
                assertTrue(filter.mightContain(member), "blockCount " + blockCount);
            }
        }
    }

    @Test
    void aRightSizedFilterRejectsMostNonMembers() {
        final List<byte[]> members = ids(HEX, 0, 20_000);
        final BloomFilter filter = of(members, BloomFilter.blockCountFor(members.size(), 0.01));
        final SplittableRandom random = new SplittableRandom(7);
        int rejected = 0;
        for (int i = 0; i < 5000; i++) {
            if (!filter.mightContain(("absent-" + random.nextLong())
                    .getBytes(StandardCharsets.UTF_8))) {
                rejected++;
            }
        }
        assertTrue(rejected > 4800, "only rejected " + rejected + " of 5000");
    }

    @Test
    void blockCountForZeroIdentifiersOrDisabledIsZero() {
        assertEquals(0, BloomFilter.blockCountFor(0, 0.01));
        assertEquals(0, BloomFilter.blockCountFor(1_000_000, 1.0));
        assertEquals(0, BloomFilter.blockCountFor(1_000_000, 2.5));
    }

    @Test
    void blockCountForScalesWithIdentifierCountAndTightensWithFpr() {
        assertTrue(BloomFilter.blockCountFor(1_000_000, 0.01) >= 1);
        assertTrue(BloomFilter.blockCountFor(10_000_000, 0.01)
                > BloomFilter.blockCountFor(1_000_000, 0.01));
        assertTrue(BloomFilter.blockCountFor(1_000_000, 0.001)
                > BloomFilter.blockCountFor(1_000_000, 0.01));
    }

    @Test
    void emptyFilterIsZeroBytesAndNoView() {
        assertEquals(0, BloomFilter.build(List.of(), 0).length);
        assertNull(BloomFilter.view(ByteBuffer.allocate(0), 0, 0));
    }
}
