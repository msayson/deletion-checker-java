package com.marksayson.deletionchecker.benchmark;

/**
 * Latency percentiles over a sorted array of per-call nanosecond timings.
 *
 * @param p50 median
 * @param p99 99th percentile
 * @param p999 99.9th percentile
 * @param max slowest call
 */
record Percentiles(long p50, long p99, long p999, long max) {

    static Percentiles of(final long[] sortedNanos) {
        final int n = sortedNanos.length;
        return new Percentiles(
                sortedNanos[n / 2],
                sortedNanos[(int) (n * 0.99)],
                sortedNanos[(int) (n * 0.999)],
                sortedNanos[n - 1]);
    }
}
