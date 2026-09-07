package com.marksayson.deletionchecker.manifest;

import com.marksayson.deletionchecker.checksum.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
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
                new EntityTypeEntry("user", "u.dat", 1, "crc32c:00000001"),
                new EntityTypeEntry("order", "o.dat", 2, "crc32c:00000002")));
        assertEquals(
                "\"entityTypes\":[{\"checksum\":\"crc32c:00000001\",\"entityType\":\"user\","
                        + "\"fileName\":\"u.dat\",\"identifierCount\":1},"
                        + "{\"checksum\":\"crc32c:00000002\",\"entityType\":\"order\","
                        + "\"fileName\":\"o.dat\",\"identifierCount\":2}]",
                canonical.substring(canonical.indexOf("\"entityTypes\""), canonical.indexOf(",\"formatVersion\"")));
    }

    @Test
    void isDeterministic() {
        final DatasetManifest manifest =
                manifest(new EntityTypeEntry("user", "u.dat", 10, "crc32c:0000000a"));
        assertEquals(
                ManifestCanonicalizer.canonicalize(manifest),
                ManifestCanonicalizer.canonicalize(manifest));
    }

    @Test
    void rejectsStringValuesNeedingEscapes() {
        // A quote via fileName, a backslash and a control char via entityType: each clears the
        // entry's own bare-name / ASCII checks but must be rejected here to keep the form escape-free.
        rejectsCanonicalization(new EntityTypeEntry("user", "has\"quote.dat", 1, "crc32c:00000001"));
        rejectsCanonicalization(new EntityTypeEntry("ba\\d", "u.dat", 1, "crc32c:00000001"));
        rejectsCanonicalization(new EntityTypeEntry("ba\td", "u.dat", 1, "crc32c:00000001"));
    }

    private static void rejectsCanonicalization(final EntityTypeEntry entry) {
        assertThrows(IllegalArgumentException.class,
                () -> ManifestCanonicalizer.canonicalize(manifest(entry)));
    }

    @Test
    void checksumIsSha256OfTheCanonicalFormWithThePrefix() {
        final String canonical = ManifestCanonicalizer.canonicalize(
                manifest(new EntityTypeEntry("user", "u.dat", 1, "crc32c:00000001")));
        final String expected = "sha256:" + HexFormat.of().formatHex(
                Sha256.of(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, ManifestCanonicalizer.checksum(canonical));
        assertEquals("sha256:", ManifestCanonicalizer.CHECKSUM_PREFIX);
    }
}
