package com.marksayson.deletionchecker.benchmark;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.IdentifierCodec;
import com.marksayson.deletionchecker.format.PackedDeletionSet;
import com.marksayson.deletionchecker.format.PrefixIndex;
import com.marksayson.deletionchecker.generator.GeneratorConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Comparative benchmark: {@link DeletionChecker} vs a plain {@code HashSet<String>} baseline, across
 * identifier shape, deleted-set size, and entity-type count. Local only — {@code
 * ./gradlew :benchmarks:benchmark}. Writes {@code build/reports/benchmarks/}; with {@code
 * -Dbench.publish=true} also refreshes {@code docs/benchmarks/reference.md}.
 */
@Tag("bench")
class ComparativeBenchmarkTest {

    private static final String TYPE = "bench";
    private static final GeneratorConfig CONFIG =
            new GeneratorConfig("1.0.0", "2026-01-01T00:00:00Z", PrefixIndex.DEFAULT_BUCKET_SIZE);

    private static final List<IdShape> SHAPES = Benchmarks.shapes("bench.shapes", "uuid,alnum16,customer");
    private static final List<Integer> SIZES =
            Benchmarks.ints("bench.sizes", "1000,10000,100000,1000000,10000000");
    private static final List<Integer> TYPE_COUNTS = Benchmarks.ints("bench.typeCounts", "1,5,10");
    private static final int TYPE_SWEEP_SIZE =
            Integer.parseInt(System.getProperty("bench.typeSweepSize", "1000000"));
    private static final int MEASURED =
            Integer.parseInt(System.getProperty("bench.measured", "500000"));
    private static final boolean PUBLISH = Boolean.getBoolean("bench.publish");

    private static final List<BenchmarkResult> RESULTS = new ArrayList<>();
    private static boolean warmedUp;

    /** A discarded small cell so the build-cost numbers are not first-invocation cold. */
    private static synchronized void warmUp() {
        if (warmedUp) {
            return;
        }
        warmedUp = true;
        final List<String> ids = IdShape.UUID.unique(0L, 20_000);
        final Path dir = Benchmarks.newCellDir();
        Benchmarks.generate(dir, Map.of(TYPE, ids), CONFIG);
        final DeletionChecker checker = Benchmarks.load(dir, Set.of(TYPE));
        for (int i = 0; i < 3; i++) {
            for (final String id : ids) {
                checker.isDeleted(TYPE, id);
            }
        }
        Benchmarks.gc();
    }

    @Test
    void sweepAperTypeScaling() {
        warmUp();
        for (final IdShape shape : SHAPES) {
            for (final int size : SIZES) {
                runSweepACell(shape, size);
                Benchmarks.gc();
            }
        }
    }

    @Test
    void sweepBentityTypeCountScaling() {
        warmUp();
        for (final int typeCount : TYPE_COUNTS) {
            runSweepBCell(typeCount);
            Benchmarks.gc();
        }
    }

    @AfterAll
    static void writeReport() {
        if (RESULTS.isEmpty()) {
            return;
        }
        RESULTS.sort(Comparator.comparing(BenchmarkResult::sweep)
                .thenComparing(BenchmarkResult::shape)
                .thenComparingInt(BenchmarkResult::sizePerType)
                .thenComparingInt(BenchmarkResult::typeCount)
                .thenComparing(BenchmarkResult::impl));
        final BenchmarkReport report = new BenchmarkReport(List.copyOf(RESULTS));

        report.writeTo(Path.of("build/reports/benchmarks"));
        final boolean bothSweeps = RESULTS.stream().anyMatch(r -> "A".equals(r.sweep()))
                && RESULTS.stream().anyMatch(r -> "B".equals(r.sweep()));
        if (PUBLISH && bothSweeps) {
            report.writeTo(Path.of("..", "docs", "benchmarks"));
            Benchmarks.log("published docs/benchmarks/reference.md");
        }
    }

    // --- Sweep A -------------------------------------------------------------------------------

    private void runSweepACell(final IdShape shape, final int size) {
        Benchmarks.log("sweep A  %-9s size=%,d", shape.flag(), size);

        final long base = HeapFootprint.used();
        final List<String> deleted = shape.unique(Benchmarks.seed(shape.ordinal(), size, 1), size);
        final Set<String> deletedSet = new java.util.HashSet<>(deleted.size());
        final long buildStart = System.nanoTime();
        deletedSet.addAll(deleted);
        final long buildMillis = Benchmarks.millisSince(buildStart);
        final long hashsetHeap = HeapFootprint.used() - base;

        final String[] posProbes =
                Benchmarks.sample(deleted, Benchmarks.seed(shape.ordinal(), size, 3), MEASURED);
        final String[] negProbes = Benchmarks.sample(
                shape.absent(Benchmarks.seed(shape.ordinal(), size, 2), MEASURED, deletedSet),
                Benchmarks.seed(shape.ordinal(), size, 4), MEASURED);

        final Path dir = Benchmarks.newCellDir();
        final long generateStart = System.nanoTime();
        Benchmarks.generate(dir, Map.of(TYPE, deleted), CONFIG);
        final long generateMillis = Benchmarks.millisSince(generateStart);

        final long preLoad = HeapFootprint.used();
        final long loadStart = System.nanoTime();
        final DeletionChecker checker = Benchmarks.load(dir, Set.of(TYPE));
        final long loadMillis = Benchmarks.millisSince(loadStart);
        final long packedHeap = HeapFootprint.used() - preLoad;
        final long mappedBytes = Benchmarks.totalDatBytes(dir);

        final Benchmarks.StringCheck hs = deletedSet::contains;
        final Benchmarks.StringCheck pk = id -> checker.isDeleted(TYPE, id);
        RESULTS.add(new BenchmarkResult("A", shape.flag(), size, 1, "hashset", size,
                buildMillis, BenchmarkResult.NA, BenchmarkResult.NA, hashsetHeap, BenchmarkResult.NA,
                Benchmarks.time(posProbes, hs, true, MEASURED),
                Benchmarks.time(negProbes, hs, false, MEASURED), null, null));

        final PackedDeletionSet packed = openPacked(dir);
        final byte[][] posBytes = encode(posProbes);
        final byte[][] negBytes = encode(negProbes);
        RESULTS.add(new BenchmarkResult("A", shape.flag(), size, 1, "packed", size,
                BenchmarkResult.NA, generateMillis, loadMillis, packedHeap, mappedBytes,
                Benchmarks.time(posProbes, pk, true, MEASURED),
                Benchmarks.time(negProbes, pk, false, MEASURED),
                timeBytes(posBytes, packed::contains, true),
                timeBytes(negBytes, packed::contains, false)));
    }

    // --- Sweep B ------------------------------------------------------------------------------

    private void runSweepBCell(final int typeCount) {
        final int size = TYPE_SWEEP_SIZE;
        Benchmarks.log("sweep B  types=%d size=%,d/type", typeCount, size);

        final long idCount = (long) size * typeCount;
        final int absentPerType = MEASURED / typeCount + 1;

        final long baseline = HeapFootprint.used();
        final Map<String, List<String>> deletedByType = new LinkedHashMap<>();
        final Map<String, IdShape> shapeByType = new LinkedHashMap<>();
        for (int t = 0; t < typeCount; t++) {
            final String type = "type-" + t;
            final IdShape shape = Benchmarks.SHAPE_ROTATION[t % Benchmarks.SHAPE_ROTATION.length];
            shapeByType.put(type, shape);
            deletedByType.put(type, shape.unique(Benchmarks.seed(shape.ordinal(), size, 200 + t), size));
        }
        final long idsBytes = HeapFootprint.used() - baseline;

        final Map<String, List<String>> absentByType = new LinkedHashMap<>();
        for (int t = 0; t < typeCount; t++) {
            final String type = "type-" + t;
            final IdShape shape = shapeByType.get(type);
            final Set<String> exclude = new java.util.HashSet<>(deletedByType.get(type));
            absentByType.put(type,
                    shape.absent(Benchmarks.seed(shape.ordinal(), size, 300 + t), absentPerType, exclude));
        }
        final String[] posProbes =
                Benchmarks.spread(deletedByType, Benchmarks.seed(0, size, 900 + typeCount), MEASURED);
        final String[] negProbes =
                Benchmarks.spread(absentByType, Benchmarks.seed(0, size, 950 + typeCount), MEASURED);
        Benchmarks.gc(); // drop the transient exclusion sets before measuring the baseline

        final long preBuild = HeapFootprint.used();
        final long buildStart = System.nanoTime();
        final Map<String, Set<String>> hashsets = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> group : deletedByType.entrySet()) {
            hashsets.put(group.getKey(), new java.util.HashSet<>(group.getValue()));
        }
        final long buildMillis = Benchmarks.millisSince(buildStart);
        final long hashsetHeap = idsBytes + (HeapFootprint.used() - preBuild);

        final Path dir = Benchmarks.newCellDir();
        final long generateStart = System.nanoTime();
        Benchmarks.generate(dir, deletedByType, CONFIG);
        final long generateMillis = Benchmarks.millisSince(generateStart);

        final long preLoad = HeapFootprint.used();
        final long loadStart = System.nanoTime();
        final DeletionChecker checker = Benchmarks.load(dir, deletedByType.keySet());
        final long loadMillis = Benchmarks.millisSince(loadStart);
        final long packedHeap = HeapFootprint.used() - preLoad;

        final Benchmarks.StringCheck hs =
                id -> hashsets.get(Benchmarks.typeOf(id)).contains(Benchmarks.idOf(id));
        final Benchmarks.StringCheck pk =
                id -> checker.isDeleted(Benchmarks.typeOf(id), Benchmarks.idOf(id));
        RESULTS.add(new BenchmarkResult("B", "mixed", size, typeCount, "hashset", idCount,
                buildMillis, BenchmarkResult.NA, BenchmarkResult.NA, hashsetHeap, BenchmarkResult.NA,
                Benchmarks.time(posProbes, hs, true, MEASURED),
                Benchmarks.time(negProbes, hs, false, MEASURED), null, null));
        RESULTS.add(new BenchmarkResult("B", "mixed", size, typeCount, "packed", idCount,
                BenchmarkResult.NA, generateMillis, loadMillis, packedHeap, Benchmarks.totalDatBytes(dir),
                Benchmarks.time(posProbes, pk, true, MEASURED),
                Benchmarks.time(negProbes, pk, false, MEASURED), null, null));

        if (typeCount > 1) {
            final String only = "type-0";
            final long selPre = HeapFootprint.used();
            final long selStart = System.nanoTime();
            final DeletionChecker selective = Benchmarks.load(dir, Set.of(only));
            final long selMillis = Benchmarks.millisSince(selStart);
            final long selHeap = HeapFootprint.used() - selPre;
            final String[] selPos =
                    Benchmarks.sample(deletedByType.get(only), Benchmarks.seed(0, size, 7), MEASURED);
            final String[] selNeg =
                    Benchmarks.sample(absentByType.get(only), Benchmarks.seed(0, size, 8), MEASURED);
            RESULTS.add(new BenchmarkResult("B", "mixed", size, typeCount, "packed(1 of N)", size,
                    BenchmarkResult.NA, BenchmarkResult.NA, selMillis, selHeap,
                    Benchmarks.datBytes(dir, only),
                    Benchmarks.time(selPos, x -> selective.isDeleted(only, x), true, MEASURED),
                    Benchmarks.time(selNeg, x -> selective.isDeleted(only, x), false, MEASURED),
                    null, null));
        }
    }

    // --- contains(byte[]) measurement (comparative only) -------------------------------------

    private interface BytesCheck {
        boolean test(byte[] id);
    }

    private static Percentiles timeBytes(final byte[][] probes, final BytesCheck check,
            final boolean expected) {
        for (int warmup = 0; warmup < 3; warmup++) {
            for (final byte[] probe : probes) {
                check.test(probe);
            }
        }
        final long[] samples = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            final byte[] probe = probes[i % probes.length];
            final long start = System.nanoTime();
            final boolean hit = check.test(probe);
            samples[i] = System.nanoTime() - start;
            if (hit != expected) {
                throw new AssertionError("expected " + expected + " (bytes)");
            }
        }
        Arrays.sort(samples);
        return Percentiles.of(samples);
    }

    private static PackedDeletionSet openPacked(final Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            final Path dat =
                    files.filter(p -> p.toString().endsWith(".dat")).findFirst().orElseThrow();
            return PackedDeletionSet.open(dat, TYPE);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[][] encode(final String[] ids) {
        final byte[][] out = new byte[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            out[i] = IdentifierCodec.encode(ids[i]);
        }
        return out;
    }
}
