package com.marksayson.deletionchecker.format;

import java.util.List;

/**
 * Builds a cumulative byte-offset table for a sequence of variable-length byte arrays: entry
 * {@code i} spans {@code [offsets[i], offsets[i + 1])} in the concatenation of the arrays.
 */
final class OffsetTableBuilder {

    private OffsetTableBuilder() {
    }

    /**
     * Returns {@code items.size() + 1} offsets, where {@code offsets[0] == 0} and
     * {@code offsets[i + 1] == offsets[i] + items.get(i).length}.
     *
     * @param items the byte arrays, in order
     * @return the cumulative offset table
     */
    static int[] cumulativeOffsets(final List<byte[]> items) {
        final int[] offsets = new int[items.size() + 1];
        for (int i = 0; i < items.size(); i++) {
            offsets[i + 1] = offsets[i] + items.get(i).length;
        }
        return offsets;
    }
}
