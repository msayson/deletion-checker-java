package com.marksayson.deletionchecker.manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetManifestTest {

    private static final String TS = "2026-09-06T17:00:00Z";
    private static final String VER = "3.2.1";
    private static final String SHA = "sha256:" + "0".repeat(64);

    @TempDir
    private Path tempDir;

    private static DatasetManifest sample() {
        return new DatasetManifest(1, TS, VER, List.of(
                new EntityTypeEntry("user", "u.dat", 10, "crc32c:abcd1234"),
                new EntityTypeEntry("order", "o.dat", 20, "crc32c:0011eeff")));
    }

    private static String checksumOf(final DatasetManifest manifest) {
        return ManifestCanonicalizer.checksum(ManifestCanonicalizer.canonicalize(manifest));
    }

    private static void rejectsInvalid(final String json) {
        assertThrows(InvalidManifestException.class, () -> DatasetManifest.parse(json), json);
    }

    /** A structurally complete manifest whose {@code entityTypes} value is spliced in verbatim. */
    private static String withEntityTypes(final String entityTypesJson) {
        return "{\"formatVersion\":1,\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\"" + VER
                + "\",\"entityTypes\":" + entityTypesJson + ",\"manifestChecksum\":\"" + SHA + "\"}";
    }

    @Test
    void parsesAndVerifiesAWrittenManifest() {
        final DatasetManifest manifest = sample();
        final DatasetManifest parsed = DatasetManifest.parse(ManifestWriter.write(manifest));

        assertEquals(1, parsed.formatVersion());
        assertEquals(TS, parsed.datasetVersion());
        assertEquals(VER, parsed.generatorVersion());
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
        final DatasetManifest manifest = new DatasetManifest(1, TS, VER,
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
        assertTrue(thrown.getMessage().contains("manifestChecksum mismatch"));
    }

    @Test
    void rejectsAMalformedManifestChecksum() {
        final String json = "{\"formatVersion\":1,\"datasetVersion\":\"" + TS
                + "\",\"generatorVersion\":\"" + VER
                + "\",\"entityTypes\":[],\"manifestChecksum\":\"not-a-hash\"}";
        final InvalidManifestException thrown = assertThrows(
                InvalidManifestException.class, () -> DatasetManifest.parse(json));
        assertTrue(thrown.getMessage().contains("malformed manifestChecksum"));
    }

    @Test
    void rejectsAnUnknownFormatVersionBeforeCheckingTheChecksum() {
        final String v2 = ManifestWriter.write(new DatasetManifest(2, TS, VER, List.of()));
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
        rejectsInvalid("{\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\"" + VER
                + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}"); // no formatVersion
        rejectsInvalid("{\"formatVersion\":1,\"generatorVersion\":\"" + VER
                + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}"); // no datasetVersion
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"" + TS
                + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}"); // no generatorVersion
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\""
                + VER + "\",\"manifestChecksum\":\"" + SHA + "\"}"); // no entityTypes
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\""
                + VER + "\",\"entityTypes\":[]}"); // no manifestChecksum
    }

    @Test
    void rejectsWrongTypedTopLevelValues() {
        rejectsInvalid("{\"formatVersion\":\"1\",\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\""
                + VER + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}"); // formatVersion string
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":5,\"generatorVersion\":\"" + VER
                + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}"); // datasetVersion number
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\""
                + VER + "\",\"entityTypes\":{},\"manifestChecksum\":\"" + SHA + "\"}"); // entityTypes object
    }

    @Test
    void rejectsAnUnknownTopLevelKey() {
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\""
                + VER + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\",\"extra\":1}");
    }

    @Test
    void rejectsAnOutOfRangeFormatVersion() {
        rejectsInvalid("{\"formatVersion\":9999999999,\"datasetVersion\":\"" + TS
                + "\",\"generatorVersion\":\"" + VER + "\",\"entityTypes\":[],\"manifestChecksum\":\""
                + SHA + "\"}");
    }

    @Test
    void rejectsAManifestNestedDeeperThanTheSchemaAllows() {
        final String json = "{\"formatVersion\":1,\"datasetVersion\":\"" + TS
                + "\",\"generatorVersion\":\"" + VER
                + "\",\"entityTypes\":[[[[]]]],\"manifestChecksum\":\"" + SHA + "\"}";
        final InvalidManifestException thrown = assertThrows(
                InvalidManifestException.class, () -> DatasetManifest.parse(json));
        assertTrue(thrown.getMessage().contains("nesting"));
    }

    @Test
    void rejectsANonTimestampDatasetVersion() {
        rejectsInvalid("{\"formatVersion\":1,\"datasetVersion\":\"2026-09-06\",\"generatorVersion\":\""
                + VER + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}");
    }

    @Test
    void rejectsANonVersionGeneratorVersion() {
        rejectsInvalid(withGeneratorVersion("v3"));             // not digit-led
        rejectsInvalid(withGeneratorVersion("-1.0"));           // leading '-' is below '0'
        rejectsInvalid(withGeneratorVersion(""));               // empty
        rejectsInvalid(withGeneratorVersion("9".repeat(65)));   // over the length cap
    }

    private static String withGeneratorVersion(final String generatorVersion) {
        return "{\"formatVersion\":1,\"datasetVersion\":\"" + TS + "\",\"generatorVersion\":\""
                + generatorVersion + "\",\"entityTypes\":[],\"manifestChecksum\":\"" + SHA + "\"}";
    }

    @Test
    void rejectsMalformedEntityTypeEntries() {
        rejectsInvalid(withEntityTypes("[1]")); // element not an object
        rejectsInvalid(withEntityTypes(
                "[{\"fileName\":\"u.dat\",\"identifierCount\":1,\"checksum\":\"crc32c:00000001\"}]"));
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"u.dat\",\"identifierCount\":1,"
                        + "\"checksum\":\"crc32c:00000001\",\"extra\":1}]")); // unknown sub-key
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"u.dat\",\"identifierCount\":\"1\","
                        + "\"checksum\":\"crc32c:00000001\"}]")); // count as string
    }

    @Test
    void rejectsAnEntryWithANegativeIdentifierCount() {
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"u.dat\",\"identifierCount\":-1,"
                        + "\"checksum\":\"crc32c:00000001\"}]"));
    }

    @Test
    void rejectsAnEntryChecksumNotFormattedAsCrc32c() {
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"u.dat\",\"identifierCount\":1,"
                        + "\"checksum\":\"nope\"}]"));
    }

    @Test
    void rejectsAnEntryFileNameThatEscapesTheDatasetDirectory() {
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"sub/u.dat\",\"identifierCount\":1,"
                        + "\"checksum\":\"crc32c:00000001\"}]"));
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"..\",\"identifierCount\":1,"
                        + "\"checksum\":\"crc32c:00000001\"}]"));
    }

    @Test
    void rejectsANonAsciiEntityType() {
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"usér\",\"fileName\":\"u.dat\",\"identifierCount\":1,"
                        + "\"checksum\":\"crc32c:00000001\"}]"));
    }

    @Test
    void rejectsDuplicateEntityTypes() {
        rejectsInvalid(withEntityTypes(
                "[{\"entityType\":\"user\",\"fileName\":\"a.dat\",\"identifierCount\":1,"
                        + "\"checksum\":\"crc32c:00000001\"},"
                        + "{\"entityType\":\"user\",\"fileName\":\"b.dat\",\"identifierCount\":2,"
                        + "\"checksum\":\"crc32c:00000002\"}]"));
    }

    @Test
    void acceptsAnEmptyEntityTypesArray() {
        final DatasetManifest manifest = new DatasetManifest(1, TS, VER, List.of());
        assertEquals(manifest, DatasetManifest.parse(ManifestWriter.write(manifest)));
    }

    @Test
    void acceptsAGeneratedStyleFileName() {
        final DatasetManifest manifest = new DatasetManifest(1, TS, VER, List.of(
                new EntityTypeEntry("user", "deleted-ids-user-2026-09-06.dat", 1, "crc32c:00000001")));
        assertEquals(manifest, DatasetManifest.parse(ManifestWriter.write(manifest)));
    }
}
