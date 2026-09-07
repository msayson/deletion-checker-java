package com.marksayson.deletionchecker.generator;

import java.util.Objects;

/**
 * One {@code (entityType, id)} pair from a deletion source, tagged with the 1-based input line it
 * came from so errors can point back to it.
 *
 * @param entityType the entity type the identifier belongs to
 * @param id the deleted identifier, exactly as issued by the authoritative source
 * @param lineNumber the 1-based line number this record was read from
 */
public record DeletionRecord(String entityType, String id, long lineNumber) {

    /** Validates the record. */
    public DeletionRecord {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(id, "id");
    }
}
