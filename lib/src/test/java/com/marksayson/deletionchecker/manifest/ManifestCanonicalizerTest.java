package com.marksayson.deletionchecker.manifest;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestCanonicalizerTest {

    private static DatasetManifest manifest(final EntityTypeEntry... entries) {
        return new DatasetManifest(1, "2026-09-06T17:00:00Z", "3.2.1", List.of(entries));
    }

    @Test
    void emitsKeysInAscendingOrderWithNoWhitespaceAndNoChecksum() {
        final String canonical = ManifestCanonicalizer.canonicalize(manifest(
                new EntityTypeEntry("user", "u.dat", 10, "crc32c:abcd1234")));

        assertEquals(
                "{\"datasetVersion\":\"2026-09-06T17:00:00Z\","
                        + "\"entityTypes\":[{\"checksum\":\"crc32c:abcd1234\","
                        + "\"entityType\":\"user\",\"fileName\":\"u.dat\","
                        + "\"identifierCount\":10}],"
                        + "\"formatVersion\":1,"
                        + "\"generatorVersion\":\"3.2.1\"}",
                canonical);
    }

    @Test
    void emptyEntityTypesArray() {
        assertEquals(
                "{\"datasetVersion\":\"2026-09-06T17:00:00Z\",\"entityTypes\":[],"
                        + "\"formatVersion\":1,\"generatorVersion\":\"3.2.1\"}",
                ManifestCanonicalizer.canonicalize(manifest()));
    }

    @Test
    void multipleEntriesAreCommaSeparatedInListOrder() {
        final String canonical = ManifestCanonicalizer.canonicalize(manifest(
                new EntityTypeEntry("user", "u.dat", 1, "crc32c:1"),
                new EntityTypeEntry("order", "o.dat", 2, "crc32c:2")));
        assertEquals(
                "\"entityTypes\":[{\"checksum\":\"crc32c:1\",\"entityType\":\"user\","
                        + "\"fileName\":\"u.dat\",\"identifierCount\":1},"
                        + "{\"checksum\":\"crc32c:2\",\"entityType\":\"order\","
                        + "\"fileName\":\"o.dat\",\"identifierCount\":2}]",
                canonical.substring(canonical.indexOf("\"entityTypes\""), canonical.indexOf(",\"formatVersion\"")));
    }

    @Test
    void isDeterministic() {
        final DatasetManifest manifest = manifest(new EntityTypeEntry("user", "u.dat", 10, "crc32c:x"));
        assertEquals(
                ManifestCanonicalizer.canonicalize(manifest),
                ManifestCanonicalizer.canonicalize(manifest));
    }

    @Test
    void rejectsStringValuesNeedingEscapes() {
        assertThrows(IllegalArgumentException.class, () -> ManifestCanonicalizer.canonicalize(
                manifest(new EntityTypeEntry("user", "has\"quote.dat", 1, "crc32c:x"))));
        assertThrows(IllegalArgumentException.class, () -> ManifestCanonicalizer.canonicalize(
                manifest(new EntityTypeEntry("user", "has\\slash.dat", 1, "crc32c:x"))));
        assertThrows(IllegalArgumentException.class, () -> ManifestCanonicalizer.canonicalize(
                new DatasetManifest(1, "has\ttab", "3.2.1", List.of())));
    }
}
