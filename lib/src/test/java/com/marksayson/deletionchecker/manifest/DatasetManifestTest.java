package com.marksayson.deletionchecker.manifest;

import com.marksayson.deletionchecker.checksum.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetManifestTest {

    @TempDir
    private Path tempDir;

    private static DatasetManifest sample() {
        return new DatasetManifest(1, "2026-09-06T17:00:00Z", "3.2.1", List.of(
                new EntityTypeEntry("user", "u.dat", 10, "crc32c:abcd1234"),
                new EntityTypeEntry("order", "o.dat", 20, "crc32c:0011eeff")));
    }

    private static String checksumOf(final DatasetManifest manifest) {
        return "sha256:" + HexFormat.of().formatHex(Sha256.of(
                ManifestCanonicalizer.canonicalize(manifest).getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejectsInvalid(final String json) {
        assertThrows(InvalidManifestException.class, () -> DatasetManifest.parse(json), json);
    }

    @Test
    void parsesAndVerifiesAWrittenManifest() {
        final DatasetManifest manifest = sample();
        final DatasetManifest parsed = DatasetManifest.parse(ManifestWriter.write(manifest));

        assertEquals(1, parsed.formatVersion());
        assertEquals("2026-09-06T17:00:00Z", parsed.datasetVersion());
        assertEquals("3.2.1", parsed.generatorVersion());
        assertEquals(manifest.entityTypes(), parsed.entityTypes());
    }

    @Test
    void entryLookupFindsPresentTypesAndMissesAbsentOnes() {
        final DatasetManifest manifest = DatasetManifest.parse(ManifestWriter.write(sample()));
        assertEquals("u.dat", manifest.entry("user").orElseThrow().fileName());
        assertEquals(20L, manifest.entry("order").orElseThrow().identifierCount());
        assertEquals(Optional.empty(), manifest.entry("device"));
    }

    @Test
    void verifiesRegardlessOfWhitespaceInTheFile() {
        final DatasetManifest manifest = new DatasetManifest(1, "2026-09-06T17:00:00Z", "3.2.1",
                List.of(new EntityTypeEntry("user", "u.dat", 10, "crc32c:abcd1234")));
        final String pretty = """
                {
                  "formatVersion": 1,
                  "datasetVersion": "2026-09-06T17:00:00Z",
                  "generatorVersion": "3.2.1",
                  "entityTypes": [
                    { "entityType": "user", "fileName": "u.dat", "identifierCount": 10,
                      "checksum": "crc32c:abcd1234" }
                  ],
                  "manifestChecksum": "%s"
                }
                """.formatted(checksumOf(manifest));

        assertEquals(manifest, DatasetManifest.parse(pretty));
    }

    @Test
    void readsAndVerifiesFromAFile() throws IOException {
        final Path path = tempDir.resolve("manifest.json");
        Files.writeString(path, ManifestWriter.write(sample()));
        assertEquals(sample(), DatasetManifest.read(path));
    }

    @Test
    void missingFileThrowsIoException() {
        assertThrows(IOException.class, () -> DatasetManifest.read(tempDir.resolve("absent.json")));
    }

    @Test
    void rejectsAChecksumMismatch() {
        final String written = ManifestWriter.write(sample());
        final String tampered = written.replace("\"3.2.1\"", "\"3.2.2\""); // keeps the old checksum
        final InvalidManifestException thrown = assertThrows(
                InvalidManifestException.class, () -> DatasetManifest.parse(tampered));
        assertEquals(true, thrown.getMessage().contains("manifestChecksum mismatch"));
    }

    @Test
    void rejectsAnUnknownFormatVersionBeforeCheckingTheChecksum() {
        final String v2 = ManifestWriter.write(
                new DatasetManifest(2, "2026-09-06T17:00:00Z", "3.2.1", List.of()));
        final UnsupportedManifestVersionException thrown = assertThrows(
                UnsupportedManifestVersionException.class, () -> DatasetManifest.parse(v2));
        assertEquals(2, thrown.found());
        assertEquals(1, thrown.supported());
    }

    @Test
    void rejectsARootThatIsNotAnObject() {
        rejectsInvalid("[]");
        rejectsInvalid("\"a string\"");
        rejectsInvalid("42");
    }

    @Test
    void rejectsMissingTopLevelKeys() {
        rejectsInvalid("{\"datasetVersion\":\"v\",\"generatorVersion\":\"g\",\"entityTypes\":[],"
                + "\"manifestChecksum\":\"c\"}"); // no formatVersion
        rejectsInvalid("{\"formatVersion\":1,\"generatorVersion\":\"g\",\"entityTypes\":[],"
                + "\"manifestChecksum\":\"c\"}"); // no datasetVersion
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"v\",\"entityTypes\":[],"
                + "\"manifestChecksum\":\"c\"}"); // no generatorVersion
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"v\",\"generatorVersion\":\"g\","
                + "\"manifestChecksum\":\"c\"}"); // no entityTypes
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"v\",\"generatorVersion\":\"g\","
                + "\"entityTypes\":[]}"); // no manifestChecksum
    }

    @Test
    void rejectsWrongTypedTopLevelValues() {
        rejectsInvalid("{\"formatVersion\":\"1\",\"datasetVersion\":\"v\",\"generatorVersion\":\"g\","
                + "\"entityTypes\":[],\"manifestChecksum\":\"c\"}"); // formatVersion as string
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":5,\"generatorVersion\":\"g\","
                + "\"entityTypes\":[],\"manifestChecksum\":\"c\"}"); // datasetVersion as number
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"v\",\"generatorVersion\":\"g\","
                + "\"entityTypes\":{},\"manifestChecksum\":\"c\"}"); // entityTypes as object
    }

    @Test
    void rejectsAnUnknownTopLevelKey() {
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"v\",\"generatorVersion\":\"g\","
                + "\"entityTypes\":[],\"manifestChecksum\":\"c\",\"extra\":1}");
    }

    @Test
    void rejectsAnOutOfRangeFormatVersion() {
        rejectsInvalid("{\"formatVersion\":9999999999,\"datasetVersion\":\"v\","
                + "\"generatorVersion\":\"g\",\"entityTypes\":[],\"manifestChecksum\":\"c\"}");
    }

    @Test
    void rejectsMalformedEntityTypeEntries() {
        rejectsInvalid(withEntityTypes("[1]")); // element not an object
        rejectsInvalid(withEntityTypes(
                "[{\"fileName\":\"f\",\"identifierCount\":1,\"checksum\":\"c\"}]")); // no entityType
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"u\",\"fileName\":\"f\",\"identifierCount\":1,\"checksum\":\"c\","
                        + "\"extra\":1}]")); // unknown sub-key
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"u\",\"fileName\":\"f\",\"identifierCount\":-1,\"checksum\":\"c\"}]"));
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"u\",\"fileName\":\"f\",\"identifierCount\":\"1\",\"checksum\":\"c\"}]"));
    }

    @Test
    void rejectsDuplicateEntityTypes() {
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"u\",\"fileName\":\"a\",\"identifierCount\":1,\"checksum\":\"c\"},"
                        + "{\"entityType\":\"u\",\"fileName\":\"b\",\"identifierCount\":2,\"checksum\":\"d\"}]"));
    }

    @Test
    void acceptsAnEmptyEntityTypesArray() {
        final DatasetManifest manifest =
                new DatasetManifest(1, "2026-09-06T17:00:00Z", "3.2.1", List.of());
        assertEquals(manifest, DatasetManifest.parse(ManifestWriter.write(manifest)));
    }

    /** A structurally complete manifest whose {@code entityTypes} value is spliced in verbatim. */
    private static String withEntityTypes(final String entityTypesJson) {
        return "{\"formatVersion\":1,\"datasetVersion\":\"v\",\"generatorVersion\":\"g\","
                + "\"entityTypes\":" + entityTypesJson + ",\"manifestChecksum\":\"c\"}";
    }
}
