package com.marksayson.deletionchecker;

import com.marksayson.deletionchecker.format.CorruptDatasetException;
import com.marksayson.deletionchecker.format.PackedDeletionSet;
import com.marksayson.deletionchecker.format.PackedFileWriter;
import com.marksayson.deletionchecker.format.UnsupportedFormatVersionException;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import com.marksayson.deletionchecker.manifest.EntityTypeEntry;
import com.marksayson.deletionchecker.manifest.ManifestWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeletionCheckerTest {

    private static final String DATASET_VERSION = "2026-09-06T17:00:00Z";

    @TempDir
    private Path datasetDir;

    @TempDir
    private Path probeDir;

    private static List<byte[]> ids(final String... items) {
        final List<byte[]> list = new ArrayList<>(items.length);
        for (final String item : items) {
            list.add(item.getBytes(StandardCharsets.UTF_8));
        }
        return list;
    }

    private static List<byte[]> sequentialIds(final int count) {
        final List<byte[]> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(String.format("%08d", i).getBytes(StandardCharsets.US_ASCII));
        }
        return list;
    }

    private static String crc32cRef(final long checksum) {
        return "crc32c:" + HexFormat.of().toHexDigits((int) checksum);
    }

    /**
     * Writes {@code diskBytes} as the packed file for {@code entityType} and returns a manifest
     * entry whose checksum is that of {@code pristineBytes} (equal to {@code diskBytes} unless a
     * test is simulating corruption). The pristine checksum is read via a throwaway probe file that
     * {@code load} never touches.
     */
    private EntityTypeEntry entryFor(final String entityType, final byte[] pristineBytes,
            final byte[] diskBytes, final int identifierCount) throws IOException {
        final String fileName = "deleted-ids-" + entityType + ".dat";
        Files.write(datasetDir.resolve(fileName), diskBytes);

        final Path probe = probeDir.resolve(entityType + ".dat");
        Files.write(probe, pristineBytes);
        final long checksum = PackedDeletionSet.open(probe, entityType).checksum();

        return new EntityTypeEntry(entityType, fileName, identifierCount, crc32cRef(checksum));
    }

    private EntityTypeEntry writeType(final String entityType, final List<byte[]> sortedIds)
            throws IOException {
        final byte[] bytes = PackedFileWriter.write(entityType, sortedIds);
        return entryFor(entityType, bytes, bytes, sortedIds.size());
    }

    private void writeManifest(final EntityTypeEntry... entries) throws IOException {
        final DatasetManifest manifest =
                new DatasetManifest(1, DATASET_VERSION, "3.2.1", List.of(entries));
        Files.writeString(
                datasetDir.resolve(DatasetManifest.FILE_NAME), ManifestWriter.write(manifest));
    }

    private DeletionChecker loadSingleType(final String entityType, final List<byte[]> sortedIds)
            throws IOException {
        writeManifest(writeType(entityType, sortedIds));
        return DeletionChecker.load(datasetDir, Set.of(entityType));
    }

    @Test
    void answersMembershipAcrossAMultiTypeDataset() throws IOException {
        writeManifest(
                writeType("user", ids("alice", "bob", "carol")),
                writeType("order", ids("o-1", "o-2")));
        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of("user", "order"));

        assertTrue(checker.isDeleted("user", "bob"));
        assertFalse(checker.isDeleted("user", "dave"));
        assertTrue(checker.isDeleted("order", "o-2"));
        assertFalse(checker.isDeleted("order", "o-3"));
    }

    @Test
    void loadSucceedsWhenAnUnrequestedTypesFileIsUnreadable() throws IOException {
        final EntityTypeEntry user = writeType("user", sequentialIds(20));
        Files.write(datasetDir.resolve("deleted-ids-order.dat"), new byte[] {1, 2, 3});
        final EntityTypeEntry order =
                new EntityTypeEntry("order", "deleted-ids-order.dat", 5, "crc32c:00000000");
        writeManifest(user, order);

        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of("user"));
        assertTrue(checker.isDeleted("user", "00000005"));
    }

    @Test
    void loadRejectsAnEntityTypeAbsentFromTheManifest() throws IOException {
        writeManifest(writeType("user", ids("a")));
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> DeletionChecker.load(datasetDir, Set.of("ghost")));
        assertTrue(thrown.getMessage().contains("ghost"));
    }

    @Test
    void loadPropagatesIoExceptionWhenTheManifestIsMissing() {
        assertThrows(IOException.class, () -> DeletionChecker.load(datasetDir, Set.of("user")));
    }

    @Test
    void loadRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> DeletionChecker.load(null, Set.of("user")));
        assertThrows(NullPointerException.class, () -> DeletionChecker.load(datasetDir, null));
    }

    @Test
    void loadFailsFastWhenARequestedFileIsCorrupt() throws IOException {
        final byte[] bytes = PackedFileWriter.write("user", sequentialIds(50)).clone();
        bytes[bytes.length - 4] ^= 0x01; // flip a byte in the identifier data
        Files.write(datasetDir.resolve("deleted-ids-user.dat"), bytes);
        writeManifest(new EntityTypeEntry("user", "deleted-ids-user.dat", 50, "crc32c:00000000"));

        assertThrows(CorruptDatasetException.class,
                () -> DeletionChecker.load(datasetDir, Set.of("user")));
    }

    @Test
    void loadReportsAVersionMismatchDistinctlyFromCorruption() throws IOException {
        final byte[] bytes = PackedFileWriter.write("user", ids("a")).clone();
        bytes[4] = 2; // formatVersion field, little-endian low byte (PackedFileFormat offset 4)
        Files.write(datasetDir.resolve("deleted-ids-user.dat"), bytes);
        writeManifest(new EntityTypeEntry("user", "deleted-ids-user.dat", 1, "crc32c:00000000"));

        assertThrows(UnsupportedFormatVersionException.class,
                () -> DeletionChecker.load(datasetDir, Set.of("user")));
    }

    @Test
    void loadRejectsAFileWhoseChecksumDisagreesWithTheManifest() throws IOException {
        final EntityTypeEntry real = writeType("user", ids("a", "b"));
        writeManifest(new EntityTypeEntry(
                "user", real.fileName(), real.identifierCount(), "crc32c:deadbeef"));

        final CorruptDatasetException thrown = assertThrows(CorruptDatasetException.class,
                () -> DeletionChecker.load(datasetDir, Set.of("user")));
        assertTrue(thrown.getMessage().contains("checksum mismatch for entity type 'user'"));
    }

    @Test
    void isDeletedRejectsANotRequestedEntityType() throws IOException {
        writeManifest(
                writeType("user", ids("a")),
                writeType("order", ids("b")));
        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of("user"));

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> checker.isDeleted("order", "b"));
        assertTrue(thrown.getMessage().contains("not requested"));
    }

    @Test
    void isDeletedRejectsInvalidIdentifiers() throws IOException {
        final DeletionChecker checker = loadSingleType("user", ids("abc"));

        assertThrows(IllegalArgumentException.class, () -> checker.isDeleted("user", null));
        assertThrows(IllegalArgumentException.class, () -> checker.isDeleted("user", ""));
        assertThrows(IllegalArgumentException.class,
                () -> checker.isDeleted("user", "x".repeat(37)));
        assertThrows(IllegalArgumentException.class,
                () -> checker.isDeleted("user", "\uD800")); // unpaired high surrogate
    }

    @Test
    void loadWithNoRequestedTypesOpensNothing() throws IOException {
        writeManifest(writeType("user", ids("a")));
        final DeletionChecker checker = DeletionChecker.load(datasetDir, Set.of());

        assertThrows(IllegalArgumentException.class, () -> checker.isDeleted("user", "a"));
    }

    @Test
    void datasetVersionAndLoadedAtReflectTheLoadedRelease() throws IOException {
        final Instant before = Instant.now();
        final DeletionChecker checker = loadSingleType("user", ids("a"));

        assertEquals(DATASET_VERSION, checker.datasetVersion());
        assertFalse(checker.loadedAt().isBefore(before));
        assertFalse(checker.loadedAt().isAfter(Instant.now()));
    }

    @Test
    void filterIsNotYetImplemented() throws IOException {
        final DeletionChecker checker = loadSingleType("user", ids("a"));
        assertThrows(UnsupportedOperationException.class,
                () -> checker.filter("user", List.of("a"), id -> id));
    }
}
