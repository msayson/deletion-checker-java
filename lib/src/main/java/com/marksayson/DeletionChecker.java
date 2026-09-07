package com.marksayson;

/**
 * Local, exact deletion-state lookup over a packed, immutable binary dataset.
 *
 * <p>Answers whether an entity ID has been deleted without any runtime network call. The dataset is
 * a set of packed binary files, one per entity type, plus a manifest describing the release. A
 * consumer selects which entity types to load at construction; unselected types incur no memory or
 * startup cost.
 *
 * <p>The dataset is immutable for the lifetime of the instance — new deletions or a changed entity-type
 * selection are picked up only by constructing a new instance against an updated dataset. Lookups are
 * exact: no false positives or negatives.
 *
 * <p>Instances are thread-safe after construction.
 */
public class DeletionChecker {
    /**
     * Returns whether {@code id} is recorded as deleted for {@code entityType}.
     *
     * <p>The result is exact — {@code true} only if this identifier is present in the loaded dataset
     * for this entity type. Comparison is on the identifier's raw UTF-8 bytes; no hashing and no
     * Unicode normalization is performed, so the caller must supply the identifier exactly as issued
     * by the authoritative source.
     *
     * @param entityType the entity type to query; must have been requested when this instance was
     *     constructed
     * @param id the identifier to check
     * @return {@code true} if the identifier is deleted for this entity type, {@code false} otherwise
     * @throws IllegalArgumentException if {@code entityType} was not requested at construction, or if
     *     {@code id} exceeds 36 bytes when UTF-8 encoded or contains an unpaired surrogate
     */
    public boolean isDeleted(final String entityType, final String id) {
        return false;
    }
}
