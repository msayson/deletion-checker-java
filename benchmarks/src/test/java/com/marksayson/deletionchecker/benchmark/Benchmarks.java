package com.marksayson.deletionchecker.benchmark;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.generator.DatasetGenerator;
import com.marksayson.deletionchecker.generator.DeletionRecord;
import com.marksayson.deletionchecker.generator.DeletionSource;
import com.marksayson.deletionchecker.generator.GeneratorConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * Shared helpers for the {@code @Tag("bench")} suites: config parsing, deterministic id / probe
 * generation, per-call timing, dataset construction, and JVM-lifetime temp directories.
 */
final class Benchmarks {

    /** Rotated across entity types when a sweep mixes shapes. */
    static final IdShape[] SHAPE_ROTATION = {IdShape.UUID, IdShape.ALNUM16, IdShape.CUSTOMER};

    private static Path tempRoot;
    private static int cellIndex;

    private Benchmarks() {
    }

    // --- config -----------------------------------------------------------------------------

    static List<IdShape> shapes(final String property, final String fallback) {
        return Arrays.stream(System.getProperty(property, fallback).split(","))
                .map(String::trim).map(IdShape::fromFlag).toList();
    }

    static List<Integer> ints(final String property, final String fallback) {
        return Arrays.stream(System.getProperty(property, fallback).split(","))
                .map(String::trim).map(Integer::parseInt).toList();
    }

    static void log(final String format, final Object... args) {
        System.out.printf(Locale.ROOT, "[benchmark] " + format + "%n", args);
    }

    /** A stable seed from three ints, well spread so nearby inputs do not collide. */
    static long seed(final int a, final int b, final int c) {
        return ((long) a << 40) ^ ((long) b << 8) ^ c;
    }

    // --- probes -----------------------------------------------------------------------------

    /** {@code count} probes sampled with replacement from {@code pool}. */
    static String[] sample(final List<String> pool, final long seed, final int count) {
        final SplittableRandom random = new SplittableRandom(seed);
        final String[] probes = new String[count];
        for (int i = 0; i < count; i++) {
            probes[i] = pool.get(random.nextInt(pool.size()));
        }
        return probes;
    }

    /** {@code count} probes tagged {@code "type id"}, spread evenly across every entity type. */
    static String[] spread(final Map<String, List<String>> byType, final long seed, final int count) {
        final SplittableRandom random = new SplittableRandom(seed);
        final List<String> types = List.copyOf(byType.keySet());
        final String[] probes = new String[count];
        for (int i = 0; i < count; i++) {
            final String type = types.get(random.nextInt(types.size()));
            final List<String> ids = byType.get(type);
            probes[i] = type + ' ' + ids.get(random.nextInt(ids.size()));
        }
        return probes;
    }

    static String typeOf(final String taggedProbe) {
        return taggedProbe.substring(0, taggedProbe.indexOf(' '));
    }

    static String idOf(final String taggedProbe) {
        return taggedProbe.substring(taggedProbe.indexOf(' ') + 1);
    }

    // --- timing -----------------------------------------------------------------------------

    @FunctionalInterface
    interface StringCheck {
        boolean test(String probe);
    }

    /**
     * Times {@code measured} calls to {@code check} (3 warm-up passes first), asserting each returns
     * {@code expected}, and returns the sorted percentiles.
     */
    static Percentiles time(final String[] probes, final StringCheck check, final boolean expected,
            final int measured) {
        for (int warmup = 0; warmup < 3; warmup++) {
            for (final String probe : probes) {
                check.test(probe);
            }
        }
        final long[] samples = new long[measured];
        for (int i = 0; i < measured; i++) {
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

    // --- datasets ---------------------------------------------------------------------------

    static void generate(final Path dir, final Map<String, List<String>> byType,
            final GeneratorConfig config) {
        final DeletionSource source = consumer -> {
            long line = 0;
            for (final Map.Entry<String, List<String>> group : byType.entrySet()) {
                for (final String id : group.getValue()) {
                    consumer.accept(new DeletionRecord(group.getKey(), id, ++line));
                }
            }
        };
        try {
            DatasetGenerator.generate(source, dir, config);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static DeletionChecker load(final Path dir, final Set<String> types) {
        try {
            return DeletionChecker.load(dir, types);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long totalDatBytes(final Path dir) {
        return datBytesMatching(dir, name -> name.endsWith(".dat"));
    }

    static long datBytes(final Path dir, final String type) {
        return datBytesMatching(dir, name -> name.contains("-" + type + "-"));
    }

    private static long datBytesMatching(final Path dir, final java.util.function.Predicate<String> match) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> match.test(p.getFileName().toString()))
                    .mapToLong(Benchmarks::sizeOf).sum();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long sizeOf(final Path file) {
        try {
            return Files.size(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- misc -------------------------------------------------------------------------------

    static synchronized Path newCellDir() {
        try {
            if (tempRoot == null) {
                tempRoot = Files.createTempDirectory("dc-benchmark");
                Runtime.getRuntime().addShutdownHook(new Thread(Benchmarks::deleteTempRoot));
            }
            return Files.createDirectory(tempRoot.resolve("cell-" + cellIndex++));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static synchronized void deleteTempRoot() {
        if (tempRoot == null || !Files.exists(tempRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempRoot)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final IOException ignored) {
            // best effort
        }
    }

    static void gc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
    }

    static long millisSince(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
