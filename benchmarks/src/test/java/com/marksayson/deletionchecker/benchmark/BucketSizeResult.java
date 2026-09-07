package com.marksayson.deletionchecker.benchmark;

/**
 * One cell of the bucket-size sweep: a packed dataset built with a given {@code K}, loaded and
 * queried. Packed-only — there is no baseline; cells are compared against each other.
 *
 * @param shape the {@link IdShape} flag
 * @param sizePerType identifiers per entity type
 * @param typeCount number of entity types
 * @param bucketSize the prefix-index bucket target size {@code K}
 * @param idCount total identifiers across all types
 * @param bucketsPerFile {@code ceil(sizePerType / K)} — the separator-array length per file
 * @param loadMillis {@code DeletionChecker.load} of the whole dataset
 * @param heapBytes retained heap of the loaded checker (dominated by the lifted prefix indexes)
 * @param mappedBytes total size of the packed {@code .dat} files
 * @param positive {@code isDeleted} latency for present ids, spread across types
 * @param negative {@code isDeleted} latency for absent ids, spread across types
 */
record BucketSizeResult(
        String shape,
        int sizePerType,
        int typeCount,
        int bucketSize,
        long idCount,
        long bucketsPerFile,
        long loadMillis,
        long heapBytes,
        long mappedBytes,
        Percentiles positive,
        Percentiles negative) {

    double heapBytesPerId() {
        return (double) heapBytes / idCount;
    }

    double mappedBytesPerId() {
        return (double) mappedBytes / idCount;
    }
}
