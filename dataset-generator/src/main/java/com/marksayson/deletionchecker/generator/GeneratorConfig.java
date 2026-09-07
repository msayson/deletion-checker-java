package com.marksayson.deletionchecker.generator;

import com.marksayson.deletionchecker.format.PackedFileWriter;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The parameters of one generation run.
 *
 * @param generatorVersion the semantic version of this generator build, recorded in the manifest;
 *     must begin with a digit
 * @param datasetVersion the ISO-8601 timestamp identifying this run, recorded in the manifest and
 *     used to date the output filenames
 * @param bucketSize the prefix-index bucket target size {@code K} for every packed file; at least 1
 * @param bloomFpr the Bloom-filter target false-positive rate for every packed file; in
 *     {@code (0, 1]}, where {@code 1.0} writes no filter
 */
public record GeneratorConfig(
        String generatorVersion, String datasetVersion, int bucketSize, double bloomFpr) {

    /** Uses {@link PackedFileWriter#DEFAULT_BLOOM_FPR}. */
    public GeneratorConfig(
            final String generatorVersion, final String datasetVersion, final int bucketSize) {
        this(generatorVersion, datasetVersion, bucketSize, PackedFileWriter.DEFAULT_BLOOM_FPR);
    }

    /** Validates the configuration. */
    public GeneratorConfig {
        Objects.requireNonNull(generatorVersion, "generatorVersion");
        Objects.requireNonNull(datasetVersion, "datasetVersion");
        if (generatorVersion.isEmpty()
                || generatorVersion.charAt(0) < '0' || generatorVersion.charAt(0) > '9') {
            throw new IllegalArgumentException(
                    "generatorVersion must begin with a digit: '" + generatorVersion + "'");
        }
        try {
            Instant.parse(datasetVersion);
        } catch (final DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "datasetVersion must be an ISO-8601 timestamp: '" + datasetVersion + "'");
        }
        if (bucketSize < 1) {
            throw new IllegalArgumentException("bucketSize must be at least 1: " + bucketSize);
        }
        if (!(bloomFpr > 0.0) || bloomFpr > 1.0) {
            throw new IllegalArgumentException(
                    "bloomFpr must be in (0, 1]: " + bloomFpr);
        }
    }
}
