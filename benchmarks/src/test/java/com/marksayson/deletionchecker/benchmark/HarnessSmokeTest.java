package com.marksayson.deletionchecker.benchmark;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.IdentifierCodec;
import com.marksayson.deletionchecker.generator.DatasetGenerator;
import com.marksayson.deletionchecker.generator.DeletionRecord;
import com.marksayson.deletionchecker.generator.DeletionSource;
import com.marksayson.deletionchecker.generator.GeneratorConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast plumbing check for the benchmark harness — runs in {@code check} so a broken harness fails
 * the build without waiting for the full {@code benchmark} sweep.
 */
class HarnessSmokeTest {

    @TempDir
    private Path dir;

    @Test
    void idShapesAreDistinctDeterministicAndWithinTheLimit() {
        for (final IdShape shape : IdShape.values()) {
            final List<String> a = shape.unique(42L, 500);
            final List<String> b = shape.unique(42L, 500);
            assertEquals(a, b, shape + " must be deterministic");
            assertEquals(500, new HashSet<>(a).size(), shape + " must be distinct");
            for (final String id : a) {
                assertTrue(id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <= IdentifierCodec.MAX_IDENTIFIER_BYTES);
            }
            final Set<String> present = new HashSet<>(a);
            for (final String absent : shape.absent(7L, 500, present)) {
                assertFalse(present.contains(absent));
            }
        }
    }

    @Test
    void percentilesReadOffTheSortedArray() {
        final long[] sorted = new long[1000];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = i;
        }
        final Percentiles p = Percentiles.of(sorted);
        assertEquals(500, p.p50());
        assertEquals(990, p.p99());
        assertEquals(999, p.p999());
        assertEquals(999, p.max());
    }

    @Test
    void endToEndGenerateLoadAgreeWithHashSet() throws IOException {
        final List<String> ids = IdShape.CUSTOMER.unique(1L, 300);
        final Set<String> oracle = new HashSet<>(ids);

        final DeletionSource source = consumer -> {
            long line = 0;
            for (final String id : ids) {
                consumer.accept(new DeletionRecord("bench", id, ++line));
            }
        };
        DatasetGenerator.generate(source, dir,
                new GeneratorConfig("1.0.0", "2026-01-01T00:00:00Z", 16));
        final DeletionChecker checker = DeletionChecker.load(dir, Set.of("bench"));

        for (final String id : ids) {
            assertTrue(checker.isDeleted("bench", id));
        }
        for (final String absent : IdShape.CUSTOMER.absent(2L, 300, oracle)) {
            assertEquals(oracle.contains(absent), checker.isDeleted("bench", absent));
        }
    }
}
