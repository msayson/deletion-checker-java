package com.marksayson.deletionchecker.benchmark;

/**
 * One measured cell of the sweep. {@code -1} marks a metric that does not apply to the cell's
 * implementation (e.g. {@code mappedBytes} for {@code HashSet}, {@code buildMillis} for the packed
 * set, the {@code search*} percentiles outside sweep A).
 *
 * @param sweep {@code "A"} (per-type scaling) or {@code "B"} (entity-type-count scaling)
 * @param shape the {@link IdShape} flag
 * @param sizePerType identifiers per entity type
 * @param typeCount number of entity types
 * @param impl {@code "hashset"} or {@code "packed"}
 * @param idCount total identifiers across all types
 * @param buildMillis {@code HashSet} construction time
 * @param generateMillis {@code DatasetGenerator.generate} time (includes a self-validating load)
 * @param loadMillis a separate {@code DeletionChecker.load} of the generated dataset
 * @param heapBytes retained heap: {@code HashSet} = ids + map overhead; packed = the loaded checker
 * @param mappedBytes total size of the packed {@code .dat} files (off-heap, file-backed)
 * @param positive {@code isDeleted} latency for present ids
 * @param negative {@code isDeleted} latency for absent ids
 * @param searchPositive {@code PackedDeletionSet.contains(byte[])} latency, present, pre-encoded
 * @param searchNegative {@code PackedDeletionSet.contains(byte[])} latency, absent, pre-encoded
 */
record BenchmarkResult(
        String sweep,
        String shape,
        int sizePerType,
        int typeCount,
        String impl,
        long idCount,
        long buildMillis,
        long generateMillis,
        long loadMillis,
        long heapBytes,
        long mappedBytes,
        Percentiles positive,
        Percentiles negative,
        Percentiles searchPositive,
        Percentiles searchNegative) {

    static final long NA = -1L;

    double heapBytesPerId() {
        return (double) heapBytes / idCount;
    }

    double mappedBytesPerId() {
        return mappedBytes < 0 ? -1 : (double) mappedBytes / idCount;
    }

    double totalBytesPerId() {
        return (double) (heapBytes + Math.max(mappedBytes, 0)) / idCount;
    }
}
