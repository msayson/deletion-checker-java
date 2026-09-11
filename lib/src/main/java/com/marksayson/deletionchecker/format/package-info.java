/**
 * The packed binary file format: one immutable, checksummed file per entity type, both read and
 * written here. {@link com.marksayson.deletionchecker.format.PackedFileWriter} builds a file from a
 * sorted, deduplicated identifier set; {@link com.marksayson.deletionchecker.format.PackedDeletionSet}
 * memory-maps and queries one, backed by a prefix index, binary search over the identifier offsets,
 * and an optional Bloom filter that fast-paths negative lookups.
 */
package com.marksayson.deletionchecker.format;
