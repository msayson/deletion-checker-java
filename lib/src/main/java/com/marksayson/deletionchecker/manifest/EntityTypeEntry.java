package com.marksayson.deletionchecker.manifest;

import java.util.Objects;

/**
 * One entity type's entry in the dataset manifest.
 *
 * @param entityType the entity type name
 * @param fileName the packed file's name, relative to the dataset directory
 * @param identifierCount the number of unique identifiers the file holds
 * @param checksum the file's expected checksum, formatted as {@code "crc32c:<hex>"}
 */
public record EntityTypeEntry(
        String entityType, String fileName, long identifierCount, String checksum) {

    /** Validates the entry. */
    public EntityTypeEntry {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(checksum, "checksum");
        if (identifierCount < 0) {
            throw new IllegalArgumentException(
                    "identifierCount must not be negative: " + identifierCount);
        }
    }
}
