package com.marksayson.deletionchecker.generator;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generate a real dataset with {@link DatasetGenerator}, load it with {@link DeletionChecker},
 * and check membership — including the boundary shapes (empty, single, all-identical, heavily
 * duplicated) and partial entity-type selection.
 */
class DatasetIntegrationTest {

    private static final String DATASET_DATE = "2026-09-06";

    @TempDir
    private Path datasetDir;

    private DatasetManifest generate(final List<DeletionRecord> records) throws IOException {
        return DatasetGenerator.generate(
                consumer -> records.forEach(consumer),
                datasetDir,
                new GeneratorConfig("1.0.0", DATASET_DATE + "T17:00:00Z", 16));
    }

    private DatasetManifest generate(final DeletionRecord... records) throws IOException {
        return generate(Arrays.asList(records));
    }

    private static DeletionRecord record(final String entityType, final String id) {
        return new DeletionRecord(entityType, id, 1);
    }

    private static String datFile(final String entityType) {
        return "deleted-ids-" + entityType + "-" + DATASET_DATE + ".dat";
    }

    @Test
    void loadsAPartialSelectionAndNeverOpensTheUnrequestedFile() throws IOException {
        generate(
                record("user", "u-1"), record("order", "o-1"),
                record("device", "d-1"), record("user", "u-2"));

        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of("user", "device"));
        assertTrue(checker.isDeleted("user", "u-2"));
        assertTrue(checker.isDeleted("device", "d-1"));
        assertFalse(checker.isDeleted("user", "u-9"));
        assertEquals(List.of("keep"),
                checker.filter("user", List.of("keep", "u-1"), id -> id));
        assertThrows(IllegalArgumentException.class, () -> checker.isDeleted("order", "o-1"));

        // 'order' was listed in the manifest but never mapped, so losing its file changes nothing.
        Files.delete(datasetDir.resolve(datFile("order")));
        assertTrue(DeletionChecker.load(datasetDir, Set.of("user")).isDeleted("user", "u-1"));
    }

    @Test
    void emptyDataset() throws IOException {
        assertTrue(generate().entityTypes().isEmpty());

        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of());
        assertThrows(IllegalArgumentException.class, () -> checker.isDeleted("user", "anything"));
    }

    @Test
    void singleIdentifierDataset() throws IOException {
        assertEquals(1L, generate(record("user", "only")).entityTypes().get(0).identifierCount());

        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of("user"));
        assertTrue(checker.isDeleted("user", "only"));
        assertFalse(checker.isDeleted("user", "onl"));
        assertFalse(checker.isDeleted("user", "only1"));
    }

    @Test
    void allIdentifiersIdenticalCollapseToOne() throws IOException {
        final DeletionRecord[] thousandSame = new DeletionRecord[1000];
        Arrays.fill(thousandSame, record("user", "dup"));

        assertEquals(1L, generate(thousandSame).entityTypes().get(0).identifierCount());
        assertTrue(DeletionChecker.load(datasetDir, Set.of("user")).isDeleted("user", "dup"));
    }

    @Test
    void heavilyDuplicatedInputReducesToTheUniqueSet() throws IOException {
        final List<DeletionRecord> records = new ArrayList<>();
        for (int repeat = 0; repeat < 20; repeat++) {
            for (int i = 0; i < 600; i++) {
                records.add(record("user", "id-" + i));
            }
        }

        assertEquals(600L, generate(records).entityTypes().get(0).identifierCount());

        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of("user"));
        for (int i = 0; i < 600; i++) {
            assertTrue(checker.isDeleted("user", "id-" + i));
        }
        assertFalse(checker.isDeleted("user", "id-600"));
    }

    @Test
    void randomMembershipHoldsPerTypeAgainstATreeSetOracle() throws IOException {
        final Random random = new Random(20260906L);
        final Map<String, TreeSet<String>> deleted = new LinkedHashMap<>();
        deleted.put("account", new TreeSet<>());
        deleted.put("session", new TreeSet<>());

        final List<DeletionRecord> records = new ArrayList<>();
        for (final Map.Entry<String, TreeSet<String>> type : deleted.entrySet()) {
            while (type.getValue().size() < 1200) {
                final String id = Long.toHexString(random.nextLong() >>> 24);
                if (type.getValue().add(id)) {
                    records.add(record(type.getKey(), id));
                }
            }
        }

        generate(records);
        final DeletionChecker checker = DeletionChecker.load(datasetDir, deleted.keySet());

        for (final Map.Entry<String, TreeSet<String>> type : deleted.entrySet()) {
            for (final String id : type.getValue()) {
                assertTrue(checker.isDeleted(type.getKey(), id), type.getKey() + " " + id);
            }
            for (int probe = 0; probe < 1200; probe++) {
                final String candidate = Long.toHexString(random.nextLong() >>> 24);
                assertEquals(
                        type.getValue().contains(candidate),
                        checker.isDeleted(type.getKey(), candidate),
                        type.getKey() + " " + candidate);
            }
        }
    }
}
