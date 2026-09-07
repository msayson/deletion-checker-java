package com.marksayson.deletionchecker.benchmark;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.IdentifierCodec;
import com.marksayson.deletionchecker.format.PackedDeletionSet;
import com.marksayson.deletionchecker.format.PrefixIndex;
import com.marksayson.deletionchecker.generator.DatasetGenerator;
import com.marksayson.deletionchecker.generator.DeletionRecord;
import com.marksayson.deletionchecker.generator.DeletionSource;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    private static final IdShape[] ROTATION = {IdShape.UUID, IdShape.ALNUM16, IdShape.CUSTOMER};

    private static final List<IdShape> SHAPES = shapes();
    private static final List<Integer> SIZES = ints("bench.sizes", "1000,10000,100000,1000000,10000000");
    private static final List<Integer> TYPE_COUNTS = ints("bench.typeCounts", "1,5,10");
    private static final int TYPE_SWEEP_SIZE =
            Integer.parseInt(System.getProperty("bench.typeSweepSize", "1000000"));
    private static final int MEASURED =
            Integer.parseInt(System.getProperty("bench.measured", "500000"));
    private static final boolean PUBLISH = Boolean.getBoolean("bench.publish");

    private static final List<BenchmarkResult> RESULTS = new ArrayList<>();
    private static Path tempRoot;
    private static int cellIndex;
    private static boolean warmedUp;

    /** A discarded small cell so the build-cost numbers are not first-invocation cold. */
    private static synchronized void warmUp() {
        if (warmedUp) {
            return;
        }
        warmedUp = true;
        final List<String> ids = IdShape.UUID.unique(0L, 20_000);
        final Path dir = newCellDir();
        generate(dir, Map.of(TYPE, ids));
        final DeletionChecker checker = load(dir, Set.of(TYPE));
        for (int i = 0; i < 3; i++) {
            for (final String id : ids) {
                checker.isDeleted(TYPE, id);
            }
        }
        gc();
    }

    @Test
    void sweepAperTypeScaling() {
        warmUp();
        for (final IdShape shape : SHAPES) {
            for (final int size : SIZES) {
                runSweepACell(shape, size);
                gc();
            }
        }
    }

    @Test
    void sweepBentityTypeCountScaling() {
        warmUp();
        for (final int typeCount : TYPE_COUNTS) {
            runSweepBCell(typeCount);
            gc();
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
            log("published docs/benchmarks/reference.md");
        }
        deleteTempRoot();
    }

    // --- Sweep A -------------------------------------------------------------------------------

    private void runSweepACell(final IdShape shape, final int size) {
        log("sweep A  %-9s size=%,d", shape.flag(), size);

        final long base = HeapFootprint.used();
        final List<String> deleted = shape.unique(seed(shape, size, 1), size);
        final Set<String> deletedSet = new java.util.HashSet<>(deleted.size());
        final long buildStart = System.nanoTime();
        deletedSet.addAll(deleted);
        final long buildMillis = millisSince(buildStart);
        final long hashsetHeap = HeapFootprint.used() - base;

        final String[] posProbes = sample(deleted, seed(shape, size, 3));
        final String[] negProbes = sample(shape.absent(seed(shape, size, 2), MEASURED, deletedSet),
                seed(shape, size, 4));

        final Path dir = newCellDir();
        final long generateStart = System.nanoTime();
        generate(dir, Map.of(TYPE, deleted));
        final long generateMillis = millisSince(generateStart);

        final long preLoad = HeapFootprint.used();
        final long loadStart = System.nanoTime();
        final DeletionChecker checker = load(dir, Set.of(TYPE));
        final long loadMillis = millisSince(loadStart);
        final long packedHeap = HeapFootprint.used() - preLoad;
        final long mappedBytes = totalDatBytes(dir);

        crossCheck(deletedSet, checker, posProbes, negProbes);

        final Predicate<String> hs = deletedSet::contains;
        final Predicate<String> pk = id -> checker.isDeleted(TYPE, id);
        RESULTS.add(new BenchmarkResult("A", shape.flag(), size, 1, "hashset", size,
                buildMillis, BenchmarkResult.NA, BenchmarkResult.NA, hashsetHeap, BenchmarkResult.NA,
                time(posProbes, hs, true), time(negProbes, hs, false), null, null));

        final PackedDeletionSet packed = openPacked(dir);
        final byte[][] posBytes = encode(posProbes);
        final byte[][] negBytes = encode(negProbes);
        RESULTS.add(new BenchmarkResult("A", shape.flag(), size, 1, "packed", size,
                BenchmarkResult.NA, generateMillis, loadMillis, packedHeap, mappedBytes,
                time(posProbes, pk, true), time(negProbes, pk, false),
                timeBytes(posBytes, packed::contains, true),
                timeBytes(negBytes, packed::contains, false)));
    }

    // --- Sweep B ------------------------------------------------------------------------------

    private void runSweepBCell(final int typeCount) {
        final int size = TYPE_SWEEP_SIZE;
        log("sweep B  types=%d size=%,d/type", typeCount, size);

        final long idCount = (long) size * typeCount;
        final int absentPerType = MEASURED / typeCount + 1;

        final long baseline = HeapFootprint.used();
        final Map<String, List<String>> deletedByType = new LinkedHashMap<>();
        final Map<String, IdShape> shapeByType = new LinkedHashMap<>();
        for (int t = 0; t < typeCount; t++) {
            final String type = "type-" + t;
            final IdShape shape = ROTATION[t % ROTATION.length];
            shapeByType.put(type, shape);
            deletedByType.put(type, shape.unique(seed(shape, size, 200 + t), size));
        }
        final long idsBytes = HeapFootprint.used() - baseline;

        final Map<String, List<String>> absentByType = new LinkedHashMap<>();
        for (int t = 0; t < typeCount; t++) {
            final String type = "type-" + t;
            final Set<String> exclude = new java.util.HashSet<>(deletedByType.get(type));
            absentByType.put(type,
                    shapeByType.get(type).absent(seed(shapeByType.get(type), size, 300 + t),
                            absentPerType, exclude));
        }
        final String[] posProbes = spread(deletedByType, seed(IdShape.UUID, size, 900 + typeCount));
        final String[] negProbes = spread(absentByType, seed(IdShape.UUID, size, 950 + typeCount));
        gc(); // drop the transient exclusion sets before measuring the baseline

        final long preBuild = HeapFootprint.used();
        final long buildStart = System.nanoTime();
        final Map<String, Set<String>> hashsets = new LinkedHashMap<>();
        for (final Map.Entry<String, List<String>> group : deletedByType.entrySet()) {
            hashsets.put(group.getKey(), new java.util.HashSet<>(group.getValue()));
        }
        final long buildMillis = millisSince(buildStart);
        final long hashsetHeap = idsBytes + (HeapFootprint.used() - preBuild);

        final Path dir = newCellDir();
        final long generateStart = System.nanoTime();
        generate(dir, deletedByType);
        final long generateMillis = millisSince(generateStart);

        final long preLoad = HeapFootprint.used();
        final long loadStart = System.nanoTime();
        final DeletionChecker checker = load(dir, deletedByType.keySet());
        final long loadMillis = millisSince(loadStart);
        final long packedHeap = HeapFootprint.used() - preLoad;

        final Predicate<String> hs = id -> hashsets.get(typeOf(id)).contains(idOf(id));
        final Predicate<String> pk = id -> checker.isDeleted(typeOf(id), idOf(id));
        RESULTS.add(new BenchmarkResult("B", "mixed", size, typeCount, "hashset", idCount,
                buildMillis, BenchmarkResult.NA, BenchmarkResult.NA, hashsetHeap, BenchmarkResult.NA,
                time(posProbes, hs, true), time(negProbes, hs, false), null, null));
        RESULTS.add(new BenchmarkResult("B", "mixed", size, typeCount, "packed", idCount,
                BenchmarkResult.NA, generateMillis, loadMillis, packedHeap, totalDatBytes(dir),
                time(posProbes, pk, true), time(negProbes, pk, false), null, null));

        if (typeCount > 1) {
            final String only = "type-0";
            final long selPre = HeapFootprint.used();
            final long selStart = System.nanoTime();
            final DeletionChecker selective = load(dir, Set.of(only));
            final long selMillis = millisSince(selStart);
            final long selHeap = HeapFootprint.used() - selPre;
            final String[] selPos = sample(deletedByType.get(only), seed(IdShape.UUID, size, 7));
            final String[] selNeg = sample(absentByType.get(only), seed(IdShape.UUID, size, 8));
            RESULTS.add(new BenchmarkResult("B", "mixed", size, typeCount, "packed(1 of N)", size,
                    BenchmarkResult.NA, BenchmarkResult.NA, selMillis, selHeap,
                    datBytes(dir, only), time(selPos, x -> selective.isDeleted(only, x), true),
                    time(selNeg, x -> selective.isDeleted(only, x), false), null, null));
        }
    }

    // --- measurement --------------------------------------------------------------------------

    private static Percentiles time(final String[] probes, final Predicate<String> check,
            final boolean expected) {
        for (int warmup = 0; warmup < 3; warmup++) {
            for (final String probe : probes) {
                check.test(probe);
            }
        }
        final long[] samples = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            final String probe = probes[i % probes.length];
            final long start = System.nanoTime();
            final boolean hit = check.test(probe);
            samples[i] = System.nanoTime() - start;
            if (hit != expected) {
                throw new AssertionError("expected " + expected + " for '" + probe + "'");
            }
        }
        Arrays.sort(samples);
        return Percentiles.of(samples);
    }

    private interface BytesPredicate {
        boolean test(byte[] id);
    }

    private static Percentiles timeBytes(final byte[][] probes, final BytesPredicate check,
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

    private static void crossCheck(final Set<String> oracle, final DeletionChecker checker,
            final String[] positives, final String[] negatives) {
        for (final String id : positives) {
            assertEquals(oracle.contains(id), checker.isDeleted(TYPE, id), id);
        }
        for (final String id : negatives) {
            assertEquals(oracle.contains(id), checker.isDeleted(TYPE, id), id);
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private static DeletionChecker load(final Path dir, final Set<String> types) {
        try {
            return DeletionChecker.load(dir, types);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void generate(final Path dir, final Map<String, List<String>> byType) {
        final DeletionSource source = consumer -> {
            long line = 0;
            for (final Map.Entry<String, List<String>> group : byType.entrySet()) {
                for (final String id : group.getValue()) {
                    consumer.accept(new DeletionRecord(group.getKey(), id, ++line));
                }
            }
        };
        try {
            DatasetGenerator.generate(source, dir, CONFIG);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PackedDeletionSet openPacked(final Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            final Path dat = files.filter(p -> p.toString().endsWith(".dat")).findFirst().orElseThrow();
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

    private static String[] sample(final List<String> pool, final long seed) {
        final SplittableRandom random = new SplittableRandom(seed);
        final int n = Math.min(MEASURED, pool.size());
        final String[] probes = new String[Math.max(n, 1)];
        for (int i = 0; i < probes.length; i++) {
            probes[i] = pool.get(random.nextInt(pool.size()));
        }
        return probes;
    }

    /** Probe strings tagged {@code "type id"} so one array spans every entity type. */
    private static String[] spread(final Map<String, List<String>> byType, final long seed) {
        final SplittableRandom random = new SplittableRandom(seed);
        final List<String> types = List.copyOf(byType.keySet());
        final String[] probes = new String[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            final String type = types.get(random.nextInt(types.size()));
            final List<String> ids = byType.get(type);
            probes[i] = type + ' ' + ids.get(random.nextInt(ids.size()));
        }
        return probes;
    }

    private static String typeOf(final String taggedProbe) {
        return taggedProbe.substring(0, taggedProbe.indexOf(' '));
    }

    private static String idOf(final String taggedProbe) {
        return taggedProbe.substring(taggedProbe.indexOf(' ') + 1);
    }

    private static long totalDatBytes(final Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".dat")).mapToLong(ComparativeBenchmarkTest::size).sum();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long datBytes(final Path dir, final String type) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().contains("-" + type + "-"))
                    .mapToLong(ComparativeBenchmarkTest::size).sum();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long size(final Path file) {
        try {
            return Files.size(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path newCellDir() {
        try {
            if (tempRoot == null) {
                tempRoot = Files.createTempDirectory("dc-benchmark");
            }
            return Files.createDirectory(tempRoot.resolve("cell-" + cellIndex++));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTempRoot() {
        if (tempRoot == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final IOException ignored) {
            // best effort
        }
    }

    private static long millisSince(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static long seed(final IdShape shape, final int size, final int salt) {
        return ((long) shape.ordinal() << 40) ^ ((long) size << 8) ^ salt;
    }

    private static void gc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
    }

    private static void log(final String format, final Object... args) {
        System.out.printf(Locale.ROOT, "[benchmark] " + format + "%n", args);
    }

    private static List<IdShape> shapes() {
        final String flags = System.getProperty("bench.shapes", "uuid,alnum16,customer");
        return Arrays.stream(flags.split(",")).map(String::trim).map(IdShape::fromFlag).toList();
    }

    private static List<Integer> ints(final String property, final String fallback) {
        return Arrays.stream(System.getProperty(property, fallback).split(","))
                .map(String::trim).map(Integer::parseInt).toList();
    }
}
