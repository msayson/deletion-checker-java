package com.marksayson.deletionchecker.generator;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.format.CorruptDatasetException;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import com.marksayson.deletionchecker.manifest.EntityTypeEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetGeneratorTest {

    @TempDir
    private Path outputDir;

    @TempDir
    private Path scratch;

    private static GeneratorConfig config() {
        return new GeneratorConfig("1.0.0", "2026-09-06T17:00:00Z", 4);
    }

    private static DeletionRecord rec(final String entityType, final String id) {
        return new DeletionRecord(entityType, id, 1);
    }

    private static DeletionSource source(final DeletionRecord... records) {
        return consumer -> {
            for (final DeletionRecord record : records) {
                consumer.accept(record);
            }
        };
    }

    private static List<String> entityTypes(final DatasetManifest manifest) {
        return manifest.entityTypes().stream().map(EntityTypeEntry::entityType).toList();
    }

    @Test
    void packsAMultiTypeDatasetThatLoadsAndAnswersMembership() throws IOException {
        final DatasetManifest manifest = DatasetGenerator.generate(
                source(rec("user", "alice"), rec("user", "carol"), rec("user", "bob"),
                        rec("order", "o-2"), rec("order", "o-1")),
                outputDir, config());

        assertEquals(List.of("order", "user"), entityTypes(manifest));

        final DeletionChecker checker = DeletionChecker.load(outputDir, Set.of("user", "order"));
        assertTrue(checker.isDeleted("user", "bob"));
        assertFalse(checker.isDeleted("user", "dave"));
        assertTrue(checker.isDeleted("order", "o-1"));
        assertFalse(checker.isDeleted("order", "o-9"));
    }

    @Test
    void collapsesConsecutiveDuplicatesSoIdentifierCountIsTheUniqueCount() throws IOException {
        final DatasetManifest manifest = DatasetGenerator.generate(
                source(rec("user", "x"), rec("user", "x"), rec("user", "y"), rec("user", "x")),
                outputDir, config());

        assertEquals(2L, manifest.entityTypes().get(0).identifierCount());
    }

    @Test
    void sortsByUtf8ByteOrderIncludingSupplementaryCharacters() throws IOException {
        // UTF-8 bytes: "A" (41) < "é" U+00E9 (C3 A9) < "😀" U+1F600 (F0 9F 98 80). PackedFileWriter
        // rejects input that is not strictly ascending, so a successful generate proves the sort.
        DatasetGenerator.generate(
                source(rec("t", "😀"), rec("t", "A"), rec("t", "é")), outputDir, config());

        final DeletionChecker checker = DeletionChecker.load(outputDir, Set.of("t"));
        assertTrue(checker.isDeleted("t", "A"));
        assertTrue(checker.isDeleted("t", "é"));
        assertTrue(checker.isDeleted("t", "😀"));
        assertFalse(checker.isDeleted("t", "B"));
    }

    @Test
    void namesFilesByEntityTypeAndDatasetDate() throws IOException {
        final DatasetManifest manifest = DatasetGenerator.generate(
                source(rec("user", "a")), outputDir,
                new GeneratorConfig("1.0.0", "2026-09-06T23:30:00Z", 128));

        assertEquals("deleted-ids-user-2026-09-06.dat", manifest.entityTypes().get(0).fileName());
        assertTrue(Files.exists(outputDir.resolve("deleted-ids-user-2026-09-06.dat")));
        assertTrue(Files.exists(outputDir.resolve("manifest.json")));
    }

    @Test
    void rejectsInvalidIdentifiersNamingTheOffendingLine() {
        assertRejectsRecord(new DeletionRecord("user", "", 3));
        assertRejectsRecord(new DeletionRecord("user", "x".repeat(65), 8));
        assertRejectsRecord(new DeletionRecord("user", "\uD800", 12));
    }

    @Test
    void rejectsUnusableEntityTypesNamingTheOffendingLine() {
        assertRejectsRecord(new DeletionRecord("", "x", 2));
        assertRejectsRecord(new DeletionRecord("x".repeat(65), "x", 5));
        assertRejectsRecord(new DeletionRecord("a/b", "x", 7));
        assertRejectsRecord(new DeletionRecord("a\\b", "x", 8));
        assertRejectsRecord(new DeletionRecord("has space", "x", 9));
        assertRejectsRecord(new DeletionRecord("nön-ascii", "x", 11));
    }

    @Test
    void aBadRecordFailsBeforeAnyFileIsWritten() {
        assertThrows(InvalidInputException.class, () -> DatasetGenerator.generate(
                source(rec("order", "ok"), new DeletionRecord("bad/type", "x", 2)),
                outputDir, config()));
        assertFalse(Files.exists(outputDir.resolve("deleted-ids-order-2026-09-06.dat")));
        assertFalse(Files.exists(outputDir.resolve("manifest.json")));
    }

    private void assertRejectsRecord(final DeletionRecord bad) {
        final InvalidInputException thrown = assertThrows(InvalidInputException.class,
                () -> DatasetGenerator.generate(source(bad), outputDir, config()));
        assertTrue(thrown.getMessage().contains("line " + bad.lineNumber()), thrown.getMessage());
    }

    @Test
    void selfValidationCatchesACorruptedWrite() {
        final DatasetGenerator.FileSink corrupting = (path, bytes) -> {
            final byte[] out = bytes.clone();
            if (path.toString().endsWith(".dat")) {
                out[out.length - 5] ^= 0x01;
            }
            Files.write(path, out);
        };

        assertThrows(CorruptDatasetException.class, () -> DatasetGenerator.generate(
                source(rec("user", "a"), rec("user", "b")), outputDir, config(), corrupting));
    }

    @Test
    void emptyInputProducesAnEmptyButLoadableDataset() throws IOException {
        final DatasetManifest manifest =
                DatasetGenerator.generate(source(), outputDir, config());

        assertEquals(List.of(), manifest.entityTypes());
        DeletionChecker.load(outputDir, Set.of());
        assertTrue(Files.exists(outputDir.resolve("manifest.json")));
    }

    @Test
    void producesByteIdenticalOutputRegardlessOfInputOrder() throws IOException {
        final Path first = Files.createDirectory(scratch.resolve("first"));
        final Path second = Files.createDirectory(scratch.resolve("second"));

        DatasetGenerator.generate(
                source(rec("user", "a"), rec("order", "b"), rec("user", "c")), first, config());
        DatasetGenerator.generate(
                source(rec("order", "b"), rec("user", "c"), rec("user", "a")), second, config());

        assertEquals(
                Files.readString(first.resolve("manifest.json")),
                Files.readString(second.resolve("manifest.json")));
        final String datFile = "deleted-ids-user-2026-09-06.dat";
        assertEquals(-1L, Files.mismatch(first.resolve(datFile), second.resolve(datFile)));
    }

    @Test
    void generateThenLoadMatchesAnOracleForRandomMembership() throws IOException {
        final Random random = new Random(20260907L);
        final TreeSet<String> deleted = new TreeSet<>();
        while (deleted.size() < 1500) {
            deleted.add(Long.toHexString(random.nextLong() & 0xFFFF_FFFF_FFFFL));
        }
        final List<DeletionRecord> records = new ArrayList<>();
        long line = 0;
        for (final String id : deleted) {
            records.add(new DeletionRecord("hex", id, ++line));
            records.add(new DeletionRecord("hex", id, ++line)); // duplicate every id
        }

        DatasetGenerator.generate(consumer -> records.forEach(consumer), outputDir, config());
        final DeletionChecker checker = DeletionChecker.load(outputDir, Set.of("hex"));

        for (final String id : deleted) {
            assertTrue(checker.isDeleted("hex", id), id);
        }
        for (int i = 0; i < 1500; i++) {
            final String candidate = Long.toHexString(random.nextLong() & 0xFFFF_FFFF_FFFFL);
            assertEquals(deleted.contains(candidate), checker.isDeleted("hex", candidate), candidate);
        }
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class,
                () -> DatasetGenerator.generate(null, outputDir, config()));
        assertThrows(NullPointerException.class,
                () -> DatasetGenerator.generate(source(), null, config()));
        assertThrows(NullPointerException.class,
                () -> DatasetGenerator.generate(source(), outputDir, null));
    }
}
