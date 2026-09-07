package com.marksayson.deletionchecker.manifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A parsed, verified dataset manifest (DESIGN §5.1): the release's three version fields plus one
 * entry per available entity type.
 *
 * @param formatVersion the manifest JSON schema version (always {@link #SUPPORTED_FORMAT_VERSION}
 *     for an instance returned by {@link #read})
 * @param datasetVersion the ISO-8601 timestamp identifying the generation run
 * @param generatorVersion the semantic version of the generator that produced the release
 * @param entityTypes one entry per entity type in the release, in manifest order
 */
public record DatasetManifest(
        int formatVersion,
        String datasetVersion,
        String generatorVersion,
        List<EntityTypeEntry> entityTypes) {

    /** The manifest JSON schema version this build reads and writes. */
    public static final int SUPPORTED_FORMAT_VERSION = 1;

    /** The manifest file's name within a dataset directory. */
    public static final String FILE_NAME = "manifest.json";

    private static final Set<String> MANIFEST_KEYS = Set.of(
            "datasetVersion", "entityTypes", "formatVersion", "generatorVersion", "manifestChecksum");
    private static final Set<String> ENTRY_KEYS =
            Set.of("checksum", "entityType", "fileName", "identifierCount");

    private static final int SHA256_HEX_LENGTH = 64;
    private static final int MAX_GENERATOR_VERSION_LENGTH = 64;

    /** Makes the entity-type list immutable. */
    public DatasetManifest {
        entityTypes = List.copyOf(entityTypes);
    }

    /**
     * Reads and verifies the manifest at {@code path}.
     *
     * @param path the manifest file
     * @return the parsed, verified manifest
     * @throws IOException if the file cannot be read
     * @throws InvalidManifestException if the manifest is malformed or its {@code manifestChecksum}
     *     does not match
     * @throws UnsupportedManifestVersionException if the manifest's {@code formatVersion} is not
     *     {@link #SUPPORTED_FORMAT_VERSION}
     */
    public static DatasetManifest read(final Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    static DatasetManifest parse(final String json) {
        final Object root = ManifestJson.parse(json);
        if (!(root instanceof Map<?, ?> map)) {
            throw new InvalidManifestException("manifest must be a JSON object");
        }

        final int formatVersion = intValue(map, "formatVersion");
        if (formatVersion != SUPPORTED_FORMAT_VERSION) {
            throw new UnsupportedManifestVersionException(formatVersion, SUPPORTED_FORMAT_VERSION);
        }

        final String datasetVersion = timestampValue(map, "datasetVersion");
        final String generatorVersion = generatorVersionValue(map);
        final List<EntityTypeEntry> entityTypes = entityTypeEntries(map);
        final String manifestChecksum = stringValue(map, "manifestChecksum");
        requireOnlyKeys(map, MANIFEST_KEYS, "manifest");

        final DatasetManifest manifest =
                new DatasetManifest(formatVersion, datasetVersion, generatorVersion, entityTypes);
        verifyChecksum(manifest, manifestChecksum);
        return manifest;
    }

    private static void verifyChecksum(final DatasetManifest manifest, final String actual) {
        if (!isSha256Reference(actual)) {
            throw new InvalidManifestException(
                    "malformed manifestChecksum '" + actual + "'; expected \""
                            + ManifestCanonicalizer.CHECKSUM_PREFIX + "\" followed by "
                            + SHA256_HEX_LENGTH + " lowercase hex digits");
        }
        final String expected = ManifestCanonicalizer.checksum(
                ManifestCanonicalizer.canonicalize(manifest));
        if (!expected.equals(actual)) {
            throw new InvalidManifestException(
                    "manifestChecksum mismatch: computed " + expected + ", manifest has " + actual);
        }
    }

    private static boolean isSha256Reference(final String value) {
        return ChecksumString.hasShape(
                value, ManifestCanonicalizer.CHECKSUM_PREFIX, SHA256_HEX_LENGTH);
    }

    /**
     * Returns the entry for {@code entityType}, if the release contains it.
     *
     * @param entityType the entity type to look up
     * @return the entry, or empty if the release has no such entity type
     */
    public Optional<EntityTypeEntry> entry(final String entityType) {
        for (final EntityTypeEntry candidate : entityTypes) {
            if (candidate.entityType().equals(entityType)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static List<EntityTypeEntry> entityTypeEntries(final Map<?, ?> map) {
        if (!(require(map, "entityTypes") instanceof List<?> list)) {
            throw new InvalidManifestException("'entityTypes' must be an array");
        }
        final List<EntityTypeEntry> entries = new ArrayList<>(list.size());
        final Set<String> seen = new HashSet<>();
        for (final Object element : list) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new InvalidManifestException("each 'entityTypes' element must be an object");
            }
            final String entityType = stringValue(entry, "entityType");
            final String fileName = stringValue(entry, "fileName");
            final long identifierCount = longValue(entry, "identifierCount");
            final String checksum = stringValue(entry, "checksum");
            requireOnlyKeys(entry, ENTRY_KEYS, "entityTypes entry");
            final EntityTypeEntry parsed;
            try {
                parsed = new EntityTypeEntry(entityType, fileName, identifierCount, checksum);
            } catch (final IllegalArgumentException e) {
                throw new InvalidManifestException("invalid entityTypes entry: " + e.getMessage());
            }
            if (!seen.add(entityType)) {
                throw new InvalidManifestException("duplicate entityType '" + entityType + "'");
            }
            entries.add(parsed);
        }
        return entries;
    }

    private static String timestampValue(final Map<?, ?> map, final String key) {
        final String value = stringValue(map, key);
        try {
            Instant.parse(value);
        } catch (final DateTimeParseException e) {
            throw new InvalidManifestException(
                    "'" + key + "' must be an ISO-8601 timestamp: '" + value + "'");
        }
        return value;
    }

    private static String generatorVersionValue(final Map<?, ?> map) {
        final String value = stringValue(map, "generatorVersion");
        if (!isDigitLedVersion(value)) {
            throw new InvalidManifestException(
                    "'generatorVersion' must be a version string beginning with a digit: '"
                            + value + "'");
        }
        return value;
    }

    private static boolean isDigitLedVersion(final String value) {
        return !value.isEmpty()
                && value.length() <= MAX_GENERATOR_VERSION_LENGTH
                && value.charAt(0) >= '0' && value.charAt(0) <= '9';
    }

    private static Object require(final Map<?, ?> map, final String key) {
        final Object value = map.get(key);
        if (value == null) {
            throw new InvalidManifestException("missing key '" + key + "'");
        }
        return value;
    }

    private static String stringValue(final Map<?, ?> map, final String key) {
        if (!(require(map, key) instanceof String string)) {
            throw new InvalidManifestException("'" + key + "' must be a string");
        }
        return string;
    }

    private static long longValue(final Map<?, ?> map, final String key) {
        if (!(require(map, key) instanceof Long number)) {
            throw new InvalidManifestException("'" + key + "' must be an integer");
        }
        return number;
    }

    private static int intValue(final Map<?, ?> map, final String key) {
        final long value = longValue(map, key);
        if (value != (int) value) {
            throw new InvalidManifestException("'" + key + "' is out of range: " + value);
        }
        return (int) value;
    }

    private static void requireOnlyKeys(
            final Map<?, ?> map, final Set<String> allowed, final String where) {
        for (final Object key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new InvalidManifestException("unexpected key '" + key + "' in " + where);
            }
        }
    }
}
