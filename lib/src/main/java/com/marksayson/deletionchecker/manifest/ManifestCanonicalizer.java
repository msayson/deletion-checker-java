package com.marksayson.deletionchecker.manifest;

import com.marksayson.deletionchecker.checksum.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Serializes a manifest to its canonical JSON form: keys in ascending order, no insignificant
 * whitespace, and the {@code manifestChecksum} field omitted. Generation and verification both hash
 * this exact byte sequence, so they always agree regardless of how a manifest file is actually
 * formatted.
 *
 * <p>Manifest string values must not contain a quote, a backslash, or a control character — the
 * generator's inputs (entity types, file names, versions, timestamps) never do, and forbidding them
 * keeps the canonical form escape-free and unambiguous.
 */
public final class ManifestCanonicalizer {

    /** Algorithm prefix on a {@code manifestChecksum} value: {@value}. */
    public static final String CHECKSUM_PREFIX = "sha256:";

    private ManifestCanonicalizer() {
    }

    /**
     * Returns the {@code manifestChecksum} value for the manifest whose checksum-free canonical form
     * is {@code canonicalJson}: {@link #CHECKSUM_PREFIX} followed by the lowercase-hex SHA-256 of
     * that form's UTF-8 bytes. The writer stores this; verification recomputes it the same way, so
     * both sides always agree.
     *
     * @param canonicalJson a manifest's canonical form, as returned by {@link #canonicalize}
     * @return the {@code manifestChecksum} value
     */
    public static String checksum(final String canonicalJson) {
        return CHECKSUM_PREFIX + HexFormat.of().formatHex(
                Sha256.of(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns the canonical JSON for {@code manifest}, without a {@code manifestChecksum} field.
     *
     * @param manifest the manifest to serialize
     * @return the canonical JSON string
     * @throws IllegalArgumentException if any string value contains a quote, backslash, or control
     *     character
     */
    public static String canonicalize(final DatasetManifest manifest) {
        final StringBuilder json = new StringBuilder(256);
        json.append("{\"datasetVersion\":").append(string(manifest.datasetVersion()));
        json.append(",\"entityTypes\":[");
        final List<EntityTypeEntry> entries = manifest.entityTypes();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            final EntityTypeEntry entry = entries.get(i);
            json.append("{\"checksum\":").append(string(entry.checksum()));
            json.append(",\"entityType\":").append(string(entry.entityType()));
            json.append(",\"fileName\":").append(string(entry.fileName()));
            json.append(",\"identifierCount\":").append(entry.identifierCount());
            json.append('}');
        }
        json.append("],\"formatVersion\":").append(manifest.formatVersion());
        json.append(",\"generatorVersion\":").append(string(manifest.generatorVersion()));
        json.append('}');
        return json.toString();
    }

    private static String string(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                throw new IllegalArgumentException(
                        "manifest string values must not contain quotes, backslashes, or control "
                                + "characters: " + value);
            }
        }
        return '"' + value + '"';
    }
}
