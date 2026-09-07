package com.marksayson.deletionchecker.manifest;

import com.marksayson.deletionchecker.checksum.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestWriterTest {

    private static DatasetManifest sample() {
        return new DatasetManifest(1, "2026-09-06T17:00:00Z", "3.2.1", List.of(
                new EntityTypeEntry("user", "u.dat", 10, "crc32c:abcd1234"),
                new EntityTypeEntry("order", "o.dat", 20, "crc32c:0011eeff")));
    }

    @Test
    void outputIsCanonicalFormPlusATrailingChecksumField() {
        final DatasetManifest manifest = sample();
        final String canonical = ManifestCanonicalizer.canonicalize(manifest);
        final String expectedChecksum = "sha256:" + HexFormat.of().formatHex(
                Sha256.of(canonical.getBytes(StandardCharsets.UTF_8)));

        assertEquals(
                canonical.substring(0, canonical.length() - 1)
                        + ",\"manifestChecksum\":\"" + expectedChecksum + "\"}",
                ManifestWriter.write(manifest));
    }

    @Test
    void checksumIsSha256HexOverTheChecksumFreeCanonicalForm() {
        final Object root = ManifestJson.parse(ManifestWriter.write(sample()));
        final String checksum = (String) ((Map<?, ?>) root).get("manifestChecksum");

        assertTrue(checksum.startsWith("sha256:"));
        assertEquals(7 + 64, checksum.length());
    }

    @Test
    void roundTripsThroughDatasetManifest() {
        final DatasetManifest manifest = sample();
        assertEquals(manifest, DatasetManifest.parse(ManifestWriter.write(manifest)));
    }

    @Test
    void writesAValidJsonObjectWithEveryKey() {
        final Object root = ManifestJson.parse(ManifestWriter.write(sample()));
        assertEquals(
                java.util.Set.of("formatVersion", "datasetVersion", "generatorVersion",
                        "entityTypes", "manifestChecksum"),
                ((Map<?, ?>) root).keySet());
    }
}
