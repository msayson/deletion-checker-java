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
 * Renders the bucket-size sweep to {@code bucket-size.csv} (every metric) and {@code
 * bucket-size.md} (env header, then one latency + memory block per shape, then a per-scenario
 * best-{@code K} summary).
 */
final class BucketSizeReport {

    private static final double MIB = 1024 * 1024;
    private static final double US = 1000.0;
    private static final int DEFAULT_K = 128;

    private final List<BucketSizeResult> results;

    BucketSizeReport(final List<BucketSizeResult> results) {
        this.results = results;
    }

    void writeTo(final Path directory) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("bucket-size.csv"), csv(), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("bucket-size.md"), markdown(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String csv() {
        final StringBuilder out = new StringBuilder(
                "shape,sizePerType,typeCount,bucketSize,idCount,bucketsPerFile,loadMs,"
                        + "heapMiB,heapBytesPerId,mappedMiB,mappedBytesPerId,"
                        + "posP50ns,posP99ns,posP999ns,negP50ns,negP99ns,negP999ns\n");
        for (final BucketSizeResult r : results) {
            out.append(String.join(",",
                    r.shape(), Integer.toString(r.sizePerType()), Integer.toString(r.typeCount()),
                    Integer.toString(r.bucketSize()), Long.toString(r.idCount()),
                    Long.toString(r.bucketsPerFile()), Long.toString(r.loadMillis()),
                    fixed(r.heapBytes() / MIB), fixed(r.heapBytesPerId()),
                    fixed(r.mappedBytes() / MIB), fixed(r.mappedBytesPerId()),
                    l(r.positive().p50()), l(r.positive().p99()), l(r.positive().p999()),
                    l(r.negative().p50()), l(r.negative().p99()), l(r.negative().p999())))
                    .append('\n');
        }
        return out.toString();
    }

    private String markdown() {
        final StringBuilder md = new StringBuilder();
        md.append("# Bucket size (K) sweep\n\n")
                .append("Packed set only, comparing prefix-index bucket sizes. Current default is ")
                .append("`K = ").append(DEFAULT_K).append("` (`PrefixIndex.DEFAULT_BUCKET_SIZE`). ")
                .append("Regenerate with `./gradlew :benchmarks:benchmark -Dbench.publish=true ")
                .append("--tests '*BucketSize*'`; interpretation in [analysis.md](analysis.md).\n\n");
        appendEnv(md);

        final List<Integer> ks = results.stream().map(BucketSizeResult::bucketSize).distinct().sorted().toList();
        final List<String> shapes = results.stream().map(BucketSizeResult::shape).distinct().toList();
        final List<Integer> typeCounts =
                results.stream().map(BucketSizeResult::typeCount).distinct().sorted().toList();

        for (final String shape : shapes) {
            md.append("\n## ").append(shape).append("\n");
            appendLatency(md, shape, typeCounts, ks);
            appendMemory(md, shape, typeCounts, ks);
        }
        appendBest(md, shapes, typeCounts);
        return md.toString();
    }

    private void appendEnv(final StringBuilder md) {
        final BucketSizeResult any = results.get(0);
        md.append("| | |\n| --- | --- |\n")
                .append(row("Generated", Instant.now().toString()))
                .append(row("JVM", System.getProperty("java.vm.name") + " "
                        + System.getProperty("java.version")))
                .append(row("OS", System.getProperty("os.name") + " "
                        + System.getProperty("os.version") + " / " + System.getProperty("os.arch")))
                .append(row("CPU", cpuModel() + " (" + Runtime.getRuntime().availableProcessors()
                        + " cores visible)"))
                .append(row("Per type", String.format(Locale.ROOT, "%,d ids", any.sizePerType())));
    }

    private void appendLatency(final StringBuilder md, final String shape,
            final List<Integer> typeCounts, final List<Integer> ks) {
        md.append("\n### isDeleted p99 / p50 (microseconds), positive lookups\n\n| K | buckets/file");
        for (final int tc : typeCounts) {
            md.append(" | ").append(tc).append(tc == 1 ? " type" : " types");
        }
        md.append(" |\n| ---: | ---: |").append(" ---: |".repeat(typeCounts.size())).append('\n');
        for (final int k : ks) {
            md.append("| ").append(k).append(" | ").append(bucketsFor(shape, k));
            for (final int tc : typeCounts) {
                final BucketSizeResult r = cell(shape, tc, k);
                md.append(" | ").append(us(r.positive().p99())).append(" / ")
                        .append(us(r.positive().p50()));
            }
            md.append(" |\n");
        }
        md.append("\n### isDeleted p99 / p50 (microseconds), negative lookups\n\n| K");
        for (final int tc : typeCounts) {
            md.append(" | ").append(tc).append(tc == 1 ? " type" : " types");
        }
        md.append(" |\n| ---: |").append(" ---: |".repeat(typeCounts.size())).append('\n');
        for (final int k : ks) {
            md.append("| ").append(k);
            for (final int tc : typeCounts) {
                final BucketSizeResult r = cell(shape, tc, k);
                md.append(" | ").append(us(r.negative().p99())).append(" / ")
                        .append(us(r.negative().p50()));
            }
            md.append(" |\n");
        }
    }

    private void appendMemory(final StringBuilder md, final String shape,
            final List<Integer> typeCounts, final List<Integer> ks) {
        md.append("\n### Prefix-index heap bytes/id and load ms (")
                .append(typeCounts.get(typeCounts.size() - 1)).append(" types)\n\n")
                .append("| K | heap B/id | mapped B/id | load ms |\n| ---: | ---: | ---: | ---: |\n");
        final int tc = typeCounts.get(typeCounts.size() - 1);
        for (final int k : ks) {
            final BucketSizeResult r = cell(shape, tc, k);
            md.append("| ").append(k)
                    .append(" | ").append(fixed(r.heapBytesPerId()))
                    .append(" | ").append(fixed(r.mappedBytesPerId()))
                    .append(" | ").append(r.loadMillis())
                    .append(" |\n");
        }
    }

    private void appendBest(final StringBuilder md, final List<String> shapes,
            final List<Integer> typeCounts) {
        md.append("\n## Fastest K per scenario (positive p99)\n\n")
                .append("| shape | types | best K | its p99 us | K=128 p99 us |\n")
                .append("| --- | ---: | ---: | ---: | ---: |\n");
        for (final String shape : shapes) {
            for (final int tc : typeCounts) {
                final List<BucketSizeResult> row = results.stream()
                        .filter(r -> r.shape().equals(shape) && r.typeCount() == tc).toList();
                final BucketSizeResult best = row.stream()
                        .min((a, b) -> Long.compare(a.positive().p99(), b.positive().p99())).orElseThrow();
                final BucketSizeResult base = row.stream()
                        .filter(r -> r.bucketSize() == DEFAULT_K).findFirst().orElse(best);
                md.append("| ").append(shape).append(" | ").append(tc)
                        .append(" | ").append(best.bucketSize())
                        .append(" | ").append(us(best.positive().p99()))
                        .append(" | ").append(us(base.positive().p99()))
                        .append(" |\n");
            }
        }
    }

    private BucketSizeResult cell(final String shape, final int typeCount, final int k) {
        return results.stream()
                .filter(r -> r.shape().equals(shape) && r.typeCount() == typeCount
                        && r.bucketSize() == k)
                .findFirst().orElseThrow();
    }

    private long bucketsFor(final String shape, final int k) {
        return results.stream().filter(r -> r.shape().equals(shape) && r.bucketSize() == k)
                .findFirst().orElseThrow().bucketsPerFile();
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

    private static String fixed(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String us(final long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / US);
    }

    private static String l(final long value) {
        return Long.toString(value);
    }
}
