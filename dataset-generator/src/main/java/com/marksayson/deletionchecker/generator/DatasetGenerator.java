package com.marksayson.deletionchecker.generator;

import com.marksayson.deletionchecker.DeletionChecker;
import com.marksayson.deletionchecker.IdentifierCodec;
import com.marksayson.deletionchecker.UnsignedBytes;
import com.marksayson.deletionchecker.format.PackedDeletionSet;
import com.marksayson.deletionchecker.format.PackedFileWriter;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import com.marksayson.deletionchecker.manifest.EntityTypeEntry;
import com.marksayson.deletionchecker.manifest.ManifestWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The build-time pipeline that turns a {@link DeletionSource} into a packed dataset directory:
 * group by entity type, validate and UTF-8-encode each identifier, sort by encoded bytes,
 * drop consecutive duplicates, write one packed file per entity type plus {@code manifest.json},
 * then self-validate the whole thing by loading it with {@link DeletionChecker}.
 *
 * <p>Entity types and their files are emitted in ascending entity-type order so a given input always
 * produces byte-identical output.
 */
public final class DatasetGenerator {

    /** Writes a file's bytes; the injection seam that lets a test corrupt the output. */
    @FunctionalInterface
    interface FileSink {
        void write(Path path, byte[] bytes) throws IOException;
    }

    private DatasetGenerator() {
    }

    /**
     * Generates the dataset for {@code source} into {@code outputDirectory}, which must already
     * exist.
     *
     * @param source the deletion records to pack
     * @param outputDirectory the directory to write the packed files and manifest into
     * @param config the run parameters
     * @return the manifest that was written
     * @throws IOException if the input cannot be read or the output cannot be written
     * @throws InvalidInputException if a record or identifier is malformed
     * @throws RuntimeException if the written dataset fails to load — a self-check on the writers
     */
    public static DatasetManifest generate(
            final DeletionSource source, final Path outputDirectory, final GeneratorConfig config)
            throws IOException {
        return generate(source, outputDirectory, config, Files::write);
    }

    static DatasetManifest generate(
            final DeletionSource source,
            final Path outputDirectory,
            final GeneratorConfig config,
            final FileSink sink) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sink, "sink");

        final Map<String, List<byte[]>> identifiersByType = new TreeMap<>();
        source.forEach(record -> {
            requireUsableEntityType(record);
            identifiersByType
                    .computeIfAbsent(record.entityType(), key -> new ArrayList<>())
                    .add(encode(record));
        });

        final LocalDate date =
                LocalDate.ofInstant(Instant.parse(config.datasetVersion()), ZoneOffset.UTC);

        final List<EntityTypeEntry> entries = new ArrayList<>(identifiersByType.size());
        for (final Map.Entry<String, List<byte[]>> group : identifiersByType.entrySet()) {
            final String entityType = group.getKey();
            final List<byte[]> identifiers = sortAndDeduplicate(group.getValue());
            final String fileName = "deleted-ids-" + entityType + "-" + date + ".dat";
            final Path file = outputDirectory.resolve(fileName);

            sink.write(file, PackedFileWriter.write(
                    entityType, identifiers, config.bucketSize(), config.bloomFpr()));

            final long checksum = PackedDeletionSet.open(file, entityType).checksum();
            entries.add(new EntityTypeEntry(
                    entityType, fileName, identifiers.size(),
                    EntityTypeEntry.crc32cReference(checksum)));
        }

        final DatasetManifest manifest = new DatasetManifest(
                DatasetManifest.SUPPORTED_FORMAT_VERSION,
                config.datasetVersion(), config.generatorVersion(), entries);
        sink.write(
                outputDirectory.resolve(DatasetManifest.FILE_NAME),
                ManifestWriter.write(manifest).getBytes(StandardCharsets.UTF_8));

        DeletionChecker.load(outputDirectory, identifiersByType.keySet());
        return manifest;
    }

    private static byte[] encode(final DeletionRecord record) {
        try {
            return IdentifierCodec.encode(record.id());
        } catch (final IllegalArgumentException e) {
            throw new InvalidInputException(
                    "line " + record.lineNumber() + ": invalid identifier for entity type '"
                            + record.entityType() + "': " + e.getMessage());
        }
    }

    /**
     * Rejects an entity type this generator cannot pack: it must be 1 to
     * {@link EntityTypeEntry#MAX_ENTITY_TYPE_LENGTH} printable-ASCII characters with no spaces or
     * path separators, since it goes into both the packed-file header and the output filename.
     * Checked as records stream in, so a bad feed fails before any file is written.
     */
    private static void requireUsableEntityType(final DeletionRecord record) {
        final String entityType = record.entityType();
        if (entityType.isEmpty()
                || entityType.length() > EntityTypeEntry.MAX_ENTITY_TYPE_LENGTH
                || !isPrintableAsciiPathSegment(entityType)) {
            throw new InvalidInputException(
                    "line " + record.lineNumber() + ": entity type '" + entityType
                            + "' must be 1 to " + EntityTypeEntry.MAX_ENTITY_TYPE_LENGTH
                            + " printable-ASCII characters with no spaces or path separators");
        }
    }

    private static boolean isPrintableAsciiPathSegment(final String value) {
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c <= ' ' || c > '~' || c == '/' || c == '\\') {
                return false;
            }
        }
        return true;
    }

    private static List<byte[]> sortAndDeduplicate(final List<byte[]> identifiers) {
        identifiers.sort(UnsignedBytes::lexicographicalCompare);
        final List<byte[]> unique = new ArrayList<>(identifiers.size());
        for (final byte[] identifier : identifiers) {
            if (unique.isEmpty()
                    || UnsignedBytes.lexicographicalCompare(
                            unique.get(unique.size() - 1), identifier) != 0) {
                unique.add(identifier);
            }
        }
        return unique;
    }
}
