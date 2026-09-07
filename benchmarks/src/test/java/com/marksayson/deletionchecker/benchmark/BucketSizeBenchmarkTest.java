package com.marksayson.deletionchecker.benchmark;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.generator.GeneratorConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Prefix-index bucket-size ({@code K}) sweep for the packed set — no baseline, cells compared
 * against each other. Varies {@code K} ∈ {128, 256, 512, 1024, 2048, 4096} × identifier shape ×
 * entity-type count, at a fixed {@code -Dbench.k.size} identifiers per type (default 1,000,000).
 *
 * <p>Local only. Runs under {@code ./gradlew :benchmarks:benchmark}; to run it alone add
 * {@code --tests '*BucketSize*'}. Writes {@code build/reports/benchmarks/bucket-size.*}; with
 * {@code -Dbench.publish=true} also {@code docs/benchmarks/bucket-size.*}.
 */
@Tag("bench")
class BucketSizeBenchmarkTest {

    private static final List<Integer> BUCKET_SIZES =
            Benchmarks.ints("bench.k.values", "128,256,512,1024,2048,4096");
    private static final List<IdShape> SHAPES =
            Benchmarks.shapes("bench.k.shapes", "uuid,customer,alnum16");
    private static final List<Integer> TYPE_COUNTS =
            Benchmarks.ints("bench.k.typeCounts", "1,3,5");
    private static final int SIZE_PER_TYPE =
            Integer.parseInt(System.getProperty("bench.k.size", "1000000"));
    private static final int MEASURED =
            Integer.parseInt(System.getProperty("bench.k.measured", "500000"));
    private static final boolean PUBLISH = Boolean.getBoolean("bench.publish");

    private static final List<BucketSizeResult> RESULTS = new ArrayList<>();

    @Test
    void sweepBucketSizes() {
        // Warm up the generate/load/isDeleted paths so the first real cell is not cold.
        final List<String> warm = IdShape.UUID.unique(0L, 20_000);
        final Path warmDir = Benchmarks.newCellDir();
        Benchmarks.generate(warmDir, Map.of("type-0", warm), config(128));
        final DeletionChecker warmChecker = Benchmarks.load(warmDir, Set.of("type-0"));
        for (int i = 0; i < 3; i++) {
            for (final String id : warm) {
                warmChecker.isDeleted("type-0", id);
            }
        }
        Benchmarks.gc();

        for (final IdShape shape : SHAPES) {
            for (final int typeCount : TYPE_COUNTS) {
                runScenario(shape, typeCount);
                Benchmarks.gc();
            }
        }
    }

    @AfterAll
    static void writeReport() {
        if (RESULTS.isEmpty()) {
            return;
        }
        RESULTS.sort(Comparator.comparing(BucketSizeResult::shape)
                .thenComparingInt(BucketSizeResult::typeCount)
                .thenComparingInt(BucketSizeResult::bucketSize));
        final BucketSizeReport report = new BucketSizeReport(List.copyOf(RESULTS));
        report.writeTo(Path.of("build/reports/benchmarks"));
        if (PUBLISH) {
            report.writeTo(Path.of("..", "docs", "benchmarks"));
            Benchmarks.log("published docs/benchmarks/bucket-size.md");
        }
    }

    /** Generates the id set once, then measures every {@code K} against it. */
    private void runScenario(final IdShape shape, final int typeCount) {
        final Map<String, List<String>> deletedByType = new LinkedHashMap<>();
        final Map<String, List<String>> absentByType = new LinkedHashMap<>();
        for (int t = 0; t < typeCount; t++) {
            final String type = "type-" + t;
            final List<String> ids =
                    shape.unique(Benchmarks.seed(shape.ordinal(), typeCount, t), SIZE_PER_TYPE);
            deletedByType.put(type, ids);
            absentByType.put(type, shape.absent(
                    Benchmarks.seed(shape.ordinal(), typeCount, 100 + t),
                    MEASURED / typeCount + 1, new java.util.HashSet<>(ids)));
        }
        final String[] posProbes =
                Benchmarks.spread(deletedByType, Benchmarks.seed(shape.ordinal(), typeCount, 900), MEASURED);
        final String[] negProbes =
                Benchmarks.spread(absentByType, Benchmarks.seed(shape.ordinal(), typeCount, 950), MEASURED);
        Benchmarks.gc();

        final long idCount = (long) SIZE_PER_TYPE * typeCount;
        for (final int bucketSize : BUCKET_SIZES) {
            Benchmarks.log("K sweep  %-9s types=%d  K=%,d", shape.flag(), typeCount, bucketSize);
            final Path dir = Benchmarks.newCellDir();
            Benchmarks.generate(dir, deletedByType, config(bucketSize));

            final long preLoad = HeapFootprint.used();
            final long loadStart = System.nanoTime();
            final DeletionChecker checker = Benchmarks.load(dir, deletedByType.keySet());
            final long loadMillis = Benchmarks.millisSince(loadStart);
            final long heapBytes = HeapFootprint.used() - preLoad;

            final Benchmarks.StringCheck check =
                    id -> checker.isDeleted(Benchmarks.typeOf(id), Benchmarks.idOf(id));
            RESULTS.add(new BucketSizeResult(shape.flag(), SIZE_PER_TYPE, typeCount, bucketSize,
                    idCount, bucketsPerFile(bucketSize), loadMillis, heapBytes,
                    Benchmarks.totalDatBytes(dir),
                    Benchmarks.time(posProbes, check, true, MEASURED),
                    Benchmarks.time(negProbes, check, false, MEASURED)));
        }
    }

    private static long bucketsPerFile(final int bucketSize) {
        return Math.max(1L, ((long) SIZE_PER_TYPE + bucketSize - 1) / bucketSize);
    }

    private static GeneratorConfig config(final int bucketSize) {
        return new GeneratorConfig("1.0.0", "2026-01-01T00:00:00Z", bucketSize);
    }
}
