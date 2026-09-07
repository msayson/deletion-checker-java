package com.marksayson.deletionchecker.perf;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.format.PackedDeletionSet;
import com.marksayson.deletionchecker.format.PackedFileWriter;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import com.marksayson.deletionchecker.manifest.EntityTypeEntry;
import com.marksayson.deletionchecker.manifest.ManifestWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Latency benchmark for {@link DeletionChecker#isDeleted} against sub-millisecond p99.9
 * goal. Manual — {@code ./gradlew perfTest}. Size is {@code -Dperf.size} (default 1,000,000).
 */
@Tag("perf")
class LookupPerfTest {

    private static final int SIZE = Integer.getInteger("perf.size", 1_000_000);
    private static final int MEASURED = 200_000;
    private static final long SUB_MILLISECOND_NANOS = 1_000_000L;

    @TempDir
    private static Path datasetDir;

    private static DeletionChecker checker;

    private static String key(final int value) {
        return String.format("%09d", value);
    }

    @BeforeAll
    static void buildAndLoadDataset() throws IOException {
        final List<byte[]> identifiers = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            identifiers.add(key(i).getBytes(StandardCharsets.US_ASCII)); // already ascending
        }

        final String fileName = "deleted-ids-user.dat";
        final Path file = datasetDir.resolve(fileName);
        Files.write(file, PackedFileWriter.write("user", identifiers));

        final long checksum = PackedDeletionSet.open(file, "user").checksum();
        final DatasetManifest manifest = new DatasetManifest(1, "2026-09-06T17:00:00Z", "1.0.0",
                List.of(new EntityTypeEntry(
                        "user", fileName, SIZE, EntityTypeEntry.crc32cReference(checksum))));
        Files.writeString(
                datasetDir.resolve(DatasetManifest.FILE_NAME), ManifestWriter.write(manifest));

        checker = DeletionChecker.load(datasetDir, Set.of("user"));
    }

    @Test
    void positiveLookupP999IsSubMillisecond() {
        assertUnderOneMillisecond("present identifier", measure(true));
    }

    @Test
    void negativeLookupP999IsSubMillisecond() {
        assertUnderOneMillisecond("absent identifier (misses at a bucket leaf)", measure(false));
    }

    private void assertUnderOneMillisecond(final String label, final long[] sortedNanos) {
        final long p50 = sortedNanos[MEASURED / 2];
        final long p99 = sortedNanos[MEASURED * 99 / 100];
        final long p999 = sortedNanos[MEASURED * 999 / 1000];
        final long max = sortedNanos[MEASURED - 1];
        System.out.printf(
                "isDeleted, %,d ids, %s: p50=%.2fus p99=%.2fus p99.9=%.2fus max=%.2fus%n",
                SIZE, label, p50 / 1000.0, p99 / 1000.0, p999 / 1000.0, max / 1000.0);
        assertTrue(p999 < SUB_MILLISECOND_NANOS,
                label + " p99.9 was " + p999 + "ns, over the 1ms budget");
    }

    private long[] measure(final boolean present) {
        // "." (0x2E) sorts between key(n) and key(n + 1), so an absent probe misses at a bucket leaf
        // rather than short-circuiting past the last separator.
        final Random random = new Random(present ? 1L : 2L);
        final String[] probes = new String[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            final int n = random.nextInt(SIZE);
            probes[i] = present ? key(n) : key(n) + ".";
        }

        for (int warmup = 0; warmup < 3; warmup++) {
            for (final String probe : probes) {
                checker.isDeleted("user", probe);
            }
        }

        final long[] samples = new long[MEASURED];
        for (int i = 0; i < MEASURED; i++) {
            final long start = System.nanoTime();
            final boolean deleted = checker.isDeleted("user", probes[i]);
            samples[i] = System.nanoTime() - start;
            assertEquals(present, deleted, probes[i]);
        }
        Arrays.sort(samples);
        return samples;
    }
}
