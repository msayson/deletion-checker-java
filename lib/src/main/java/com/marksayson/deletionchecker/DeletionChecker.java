package com.marksayson.deletionchecker;

import com.marksayson.deletionchecker.format.CorruptDatasetException;
import com.marksayson.deletionchecker.format.PackedDeletionSet;
import com.marksayson.deletionchecker.format.UnsupportedFormatVersionException;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import com.marksayson.deletionchecker.manifest.EntityTypeEntry;
import com.marksayson.deletionchecker.manifest.InvalidManifestException;
import com.marksayson.deletionchecker.manifest.UnsupportedManifestVersionException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Local, exact deletion-state lookup over a packed, immutable binary dataset.
 *
 * <p>Answers whether an entity ID has been deleted without any runtime network call. The dataset is
 * a set of packed binary files, one per entity type, plus a manifest describing the release. A
 * consumer selects which entity types to load with {@link #load}; unselected types are never opened
 * and incur no memory or startup cost.
 *
 * <p>The dataset is immutable for the lifetime of the instance — new deletions or a changed
 * entity-type selection are picked up only by loading a new instance against an updated dataset.
 * Lookups are exact: no false positives or negatives.
 *
 * <p>The mapped files are held for the life of the process; there is no {@code close}. Their pages
 * are off-heap, file-backed memory that counts toward process RSS and container memory limits,
 * not toward the Java heap.
 *
 * <p>Instances are thread-safe after {@link #load} returns.
 */
public final class DeletionChecker {

    private final Map<String, PackedDeletionSet> sets;
    private final String datasetVersion;
    private final Instant loadedAt;

    private DeletionChecker(
            final Map<String, PackedDeletionSet> sets,
            final String datasetVersion,
            final Instant loadedAt) {
        this.sets = sets;
        this.datasetVersion = datasetVersion;
        this.loadedAt = loadedAt;
    }

    /**
     * Loads the requested entity types from the dataset in {@code datasetDirectory}.
     *
     * <p>Reads and verifies {@code manifest.json}, confirms every requested entity type is listed in
     * it, then for each requested type opens its packed file, checks its format version and header
     * entity type, and verifies its checksum against the manifest. Any failure fails the load;
     * partially loaded state is discarded. Entity types not requested are never opened.
     *
     * @param datasetDirectory the directory holding {@code manifest.json} and the packed files
     * @param entityTypes the entity types to load
     * @return a checker over the loaded entity types
     * @throws NullPointerException if {@code datasetDirectory} or {@code entityTypes} is null
     * @throws IOException if the manifest or a requested type's file cannot be read
     * @throws IllegalArgumentException if a requested entity type is not listed in the manifest
     * @throws InvalidManifestException if the manifest is malformed or fails its checksum
     * @throws UnsupportedManifestVersionException if the manifest's schema version is not the one
     *     this build reads
     * @throws CorruptDatasetException if a requested type's file is corrupt, its header entity type
     *     does not match the manifest, or its checksum does not match the manifest
     * @throws UnsupportedFormatVersionException if a requested type's file has a binary format
     *     version this build does not read
     */
    public static DeletionChecker load(final Path datasetDirectory, final Set<String> entityTypes)
            throws IOException {
        Objects.requireNonNull(datasetDirectory, "datasetDirectory");
        Objects.requireNonNull(entityTypes, "entityTypes");

        final DatasetManifest manifest =
                DatasetManifest.read(datasetDirectory.resolve(DatasetManifest.FILE_NAME));

        // Resolve every requested type against the manifest before opening any file.
        final List<EntityTypeEntry> selected = new ArrayList<>();
        for (final String entityType : entityTypes) {
            final Optional<EntityTypeEntry> entry = manifest.entry(entityType);
            if (entry.isEmpty()) {
                throw new IllegalArgumentException(
                        "entity type '" + entityType + "' is not present in the dataset manifest");
            }
            selected.add(entry.get());
        }

        final Map<String, PackedDeletionSet> loaded = new HashMap<>();
        for (final EntityTypeEntry entry : selected) {
            final PackedDeletionSet set = PackedDeletionSet.open(
                    datasetDirectory.resolve(entry.fileName()), entry.entityType());
            final String fileChecksum =
                    "crc32c:" + HexFormat.of().toHexDigits((int) set.checksum());
            if (!fileChecksum.equals(entry.checksum())) {
                throw new CorruptDatasetException(
                        "checksum mismatch for entity type '" + entry.entityType()
                                + "': manifest expects " + entry.checksum()
                                + ", file computes " + fileChecksum);
            }
            loaded.put(entry.entityType(), set);
        }

        return new DeletionChecker(
                Map.copyOf(loaded), manifest.datasetVersion(), Instant.now());
    }

    /**
     * Returns whether {@code id} is recorded as deleted for {@code entityType}.
     *
     * <p>The result is exact — {@code true} only if this identifier is present in the loaded dataset
     * for this entity type. Comparison is on the identifier's raw UTF-8 bytes; no hashing and no
     * Unicode normalization is performed, so the caller must supply the identifier exactly as issued
     * by the authoritative source.
     *
     * @param entityType the entity type to query; must have been requested when this instance was
     *     loaded
     * @param id the identifier to check
     * @return {@code true} if the identifier is deleted for this entity type, {@code false} otherwise
     * @throws IllegalArgumentException if {@code entityType} was not requested when this instance was
     *     loaded, or if {@code id} is null, empty, exceeds 36 bytes when UTF-8 encoded, or contains
     *     an unpaired surrogate
     */
    public boolean isDeleted(final String entityType, final String id) {
        return setFor(entityType).contains(IdentifierCodec.encode(id));
    }

    /**
     * Returns the items whose extracted identifier is not recorded as deleted for {@code entityType},
     * in their original order.
     *
     * <p>Equivalent to calling {@link #isDeleted} on each item's extracted identifier and keeping
     * those that are not deleted, but resolves {@code entityType} once for the whole batch.
     *
     * @param <T> the item type
     * @param entityType the entity type to query; must have been requested when this instance was
     *     loaded
     * @param items the items to filter
     * @param idExtractor maps an item to the identifier to check
     * @return a new list containing only the items that are not deleted, in input order
     * @throws NullPointerException if {@code items} or {@code idExtractor} is null
     * @throws IllegalArgumentException if {@code entityType} was not requested when this instance was
     *     loaded, or if an extracted identifier is null, empty, exceeds 36 bytes when UTF-8 encoded,
     *     or contains an unpaired surrogate
     */
    public <T> List<T> filter(
            final String entityType, final List<T> items, final Function<T, String> idExtractor) {
        final PackedDeletionSet set = setFor(entityType);
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(idExtractor, "idExtractor");

        final List<T> kept = new ArrayList<>(items.size());
        for (final T item : items) {
            if (!set.contains(IdentifierCodec.encode(idExtractor.apply(item)))) {
                kept.add(item);
            }
        }
        return kept;
    }

    private PackedDeletionSet setFor(final String entityType) {
        final PackedDeletionSet set = sets.get(entityType);
        if (set == null) {
            throw new IllegalArgumentException(
                    "entity type '" + entityType
                            + "' was not requested when this DeletionChecker was loaded");
        }
        return set;
    }

    /**
     * Returns the loaded release's {@code datasetVersion} — the ISO-8601 timestamp identifying which
     * generation run's deletion data this instance holds.
     *
     * @return the dataset version string
     */
    public String datasetVersion() {
        return datasetVersion;
    }

    /**
     * Returns the instant {@link #load} completed, i.e. when this instance's dataset became active.
     * Operators use it with {@link #datasetVersion()} to spot instances running a stale release.
     *
     * @return the load-completion instant
     */
    public Instant loadedAt() {
        return loadedAt;
    }
}
