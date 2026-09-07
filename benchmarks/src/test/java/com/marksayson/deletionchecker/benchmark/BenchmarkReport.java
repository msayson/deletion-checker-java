package com.marksayson.deletionchecker.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Renders a sweep's {@link BenchmarkResult}s to {@code results.csv} (every metric) and a
 * human-readable {@code reference.md} (env header plus per-sweep tables).
 */
final class BenchmarkReport {

    private static final double MIB = 1024 * 1024;
    private static final double US = 1000.0;

    private final List<BenchmarkResult> results;

    BenchmarkReport(final List<BenchmarkResult> results) {
        this.results = results;
    }

    void writeTo(final Path directory) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("results.csv"), csv(), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("reference.md"), markdown(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String csv() {
        final StringBuilder out = new StringBuilder(
                "sweep,shape,sizePerType,typeCount,impl,idCount,buildMs,generateMs,loadMs,"
                        + "heapMiB,heapBytesPerId,mappedMiB,mappedBytesPerId,totalBytesPerId,"
                        + "posP50ns,posP99ns,posP999ns,negP50ns,negP99ns,negP999ns,"
                        + "searchPosP999ns,searchNegP999ns\n");
        for (final BenchmarkResult r : results) {
            out.append(String.join(",",
                    r.sweep(), r.shape(), Integer.toString(r.sizePerType()),
                    Integer.toString(r.typeCount()), r.impl(), Long.toString(r.idCount()),
                    na(r.buildMillis()), na(r.generateMillis()), na(r.loadMillis()),
                    fixed(r.heapBytes() / MIB), fixed(r.heapBytesPerId()),
                    r.mappedBytes() < 0 ? "" : fixed(r.mappedBytes() / MIB),
                    r.mappedBytesPerId() < 0 ? "" : fixed(r.mappedBytesPerId()),
                    fixed(r.totalBytesPerId()),
                    p(r.positive(), Percentiles::p50), p(r.positive(), Percentiles::p99),
                    p(r.positive(), Percentiles::p999),
                    p(r.negative(), Percentiles::p50), p(r.negative(), Percentiles::p99),
                    p(r.negative(), Percentiles::p999),
                    p(r.searchPositive(), Percentiles::p999),
                    p(r.searchNegative(), Percentiles::p999)))
                    .append('\n');
        }
        return out.toString();
    }

    private String markdown() {
        final StringBuilder md = new StringBuilder();
        md.append("# Benchmark reference results\n\n")
                .append("`DeletionChecker` (packed set + mmap + two-level search) vs a plain ")
                .append("`HashSet<String>` baseline. Regenerate with `./gradlew :benchmarks:benchmark ")
                .append("-Dbench.publish=true`; see [README.md](README.md).\n\n");
        appendEnv(md);

        if (results.stream().anyMatch(r -> "A".equals(r.sweep()))) {
            md.append("\n## Sweep A — per-type scaling (1 entity type)\n\n");
            appendSweepAMemory(md);
            appendSweepALatency(md);
            appendSweepABuild(md);
        }
        if (results.stream().anyMatch(r -> "B".equals(r.sweep()))) {
            md.append("\n## Sweep B — entity-type-count scaling\n\n");
            appendSweepB(md);
        }
        return md.toString();
    }

    private void appendEnv(final StringBuilder md) {
        md.append("| | |\n| --- | --- |\n")
                .append(row("Generated", Instant.now().toString()))
                .append(row("JVM", System.getProperty("java.vm.name") + " "
                        + System.getProperty("java.version")))
                .append(row("OS", System.getProperty("os.name") + " "
                        + System.getProperty("os.version") + " / "
                        + System.getProperty("os.arch")))
                .append(row("CPU", cpuModel() + " (" + Runtime.getRuntime().availableProcessors()
                        + " cores visible)"))
                .append(row("Max heap", fixed(Runtime.getRuntime().maxMemory() / MIB) + " MiB"));
    }

    private void appendSweepAMemory(final StringBuilder md) {
        md.append("### Memory\n\n")
                .append("| shape | ids | HashSet heap B/id | packed heap B/id | packed mapped B/id "
                        + "| packed total B/id | total ratio |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (final BenchmarkResult hs : sorted("A", "hashset")) {
            final BenchmarkResult pk = match(hs, "packed");
            md.append("| ").append(hs.shape())
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", hs.idCount()))
                    .append(" | ").append(fixed(hs.heapBytesPerId()))
                    .append(" | ").append(fixed(pk.heapBytesPerId()))
                    .append(" | ").append(fixed(pk.mappedBytesPerId()))
                    .append(" | ").append(fixed(pk.totalBytesPerId()))
                    .append(" | ").append(fixed(pk.totalBytesPerId() / hs.heapBytesPerId()))
                    .append("x |\n");
        }
    }

    private void appendSweepALatency(final StringBuilder md) {
        md.append("\n### Lookup latency (microseconds)\n\n")
                .append("| shape | ids | impl | pos p50 | pos p99 | pos p99.9 | neg p50 | neg p99 "
                        + "| neg p99.9 |\n")
                .append("| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (final BenchmarkResult r : results) {
            if (!"A".equals(r.sweep())) {
                continue;
            }
            md.append("| ").append(r.shape())
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", r.idCount()))
                    .append(" | ").append(r.impl())
                    .append(" | ").append(us(r.positive().p50()))
                    .append(" | ").append(us(r.positive().p99()))
                    .append(" | ").append(us(r.positive().p999()))
                    .append(" | ").append(us(r.negative().p50()))
                    .append(" | ").append(us(r.negative().p99()))
                    .append(" | ").append(us(r.negative().p999()))
                    .append(" |\n");
            if ("packed".equals(r.impl()) && r.searchPositive() != null) {
                md.append("| ").append(r.shape()).append(" | ")
                        .append(String.format(Locale.ROOT, "%,d", r.idCount()))
                        .append(" | packed contains() | ")
                        .append(us(r.searchPositive().p50())).append(" | ")
                        .append(us(r.searchPositive().p99())).append(" | ")
                        .append(us(r.searchPositive().p999())).append(" | ")
                        .append(us(r.searchNegative().p50())).append(" | ")
                        .append(us(r.searchNegative().p99())).append(" | ")
                        .append(us(r.searchNegative().p999())).append(" |\n");
            }
        }
    }

    private void appendSweepABuild(final StringBuilder md) {
        md.append("\n### Build cost (milliseconds)\n\n")
                .append("| shape | ids | HashSet build | packed generate (self-validates) "
                        + "| packed load |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n");
        for (final BenchmarkResult hs : sorted("A", "hashset")) {
            final BenchmarkResult pk = match(hs, "packed");
            md.append("| ").append(hs.shape())
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", hs.idCount()))
                    .append(" | ").append(hs.buildMillis())
                    .append(" | ").append(pk.generateMillis())
                    .append(" | ").append(pk.loadMillis())
                    .append(" |\n");
        }
    }

    private void appendSweepB(final StringBuilder md) {
        md.append("`sizePerType` identifiers each, shape rotated across types.\n\n")
                .append("| types | total ids | impl | build/gen ms | load ms | heap MiB "
                        + "| pos p99.9 us | neg p99.9 us |\n")
                .append("| ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (final BenchmarkResult r : results) {
            if (!"B".equals(r.sweep())) {
                continue;
            }
            md.append("| ").append(r.typeCount())
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", r.idCount()))
                    .append(" | ").append(r.impl())
                    .append(" | ").append(millis("hashset".equals(r.impl())
                            ? r.buildMillis() : r.generateMillis()))
                    .append(" | ").append(millis(r.loadMillis()))
                    .append(" | ").append(fixed(r.heapBytes() / MIB))
                    .append(" | ").append(us(r.positive().p999()))
                    .append(" | ").append(us(r.negative().p999()))
                    .append(" |\n");
        }
    }

    private List<BenchmarkResult> sorted(final String sweep, final String impl) {
        return results.stream()
                .filter(r -> sweep.equals(r.sweep()) && impl.equals(r.impl()))
                .toList();
    }

    private BenchmarkResult match(final BenchmarkResult other, final String impl) {
        return results.stream()
                .filter(r -> r.sweep().equals(other.sweep()) && r.shape().equals(other.shape())
                        && r.sizePerType() == other.sizePerType()
                        && r.typeCount() == other.typeCount() && impl.equals(r.impl()))
                .findFirst()
                .orElseThrow();
    }

    private static String row(final String key, final String value) {
        return "| " + key + " | " + value + " |\n";
    }

    private static String cpuModel() {
        try {
            for (final String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (final IOException | RuntimeException ignored) {
            // best effort
        }
        return "unknown";
    }

    private static String na(final long millis) {
        return millis < 0 ? "" : Long.toString(millis);
    }

    private static String millis(final long value) {
        return value < 0 ? "—" : Long.toString(value);
    }

    private static String fixed(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String us(final long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / US);
    }

    private interface Field {
        long get(Percentiles p);
    }

    private static String p(final Percentiles percentiles, final Field field) {
        return percentiles == null ? "" : Long.toString(field.get(percentiles));
    }
}
