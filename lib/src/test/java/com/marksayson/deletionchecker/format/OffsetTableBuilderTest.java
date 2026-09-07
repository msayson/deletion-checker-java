package com.marksayson.deletionchecker.format;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class OffsetTableBuilderTest {

    @Test
    void emptyListYieldsASingleZeroOffset() {
        assertArrayEquals(new int[] {0}, OffsetTableBuilder.cumulativeOffsets(List.of()));
    }

    @Test
    void singleItemYieldsZeroAndItsLength() {
        assertArrayEquals(new int[] {0, 3}, OffsetTableBuilder.cumulativeOffsets(List.of(new byte[3])));
    }

    @Test
    void offsetsAreTheRunningTotalOfItemLengths() {
        final List<byte[]> items = List.of(new byte[2], new byte[5], new byte[1]);
        assertArrayEquals(new int[] {0, 2, 7, 8}, OffsetTableBuilder.cumulativeOffsets(items));
    }
}
