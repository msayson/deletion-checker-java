package com.marksayson.deletionchecker.manifest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityTypeEntryTest {

    @Test
    void acceptsAValidEntry() {
        final EntityTypeEntry entry = new EntityTypeEntry("user", "u.dat", 0, "crc32c:0");
        assertEquals("user", entry.entityType());
        assertEquals(0L, entry.identifierCount());
    }

    @Test
    void rejectsANegativeIdentifierCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EntityTypeEntry("user", "u.dat", -1, "crc32c:0"));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new EntityTypeEntry(null, "u.dat", 1, "crc32c:0"));
        assertThrows(NullPointerException.class,
                () -> new EntityTypeEntry("user", null, 1, "crc32c:0"));
        assertThrows(NullPointerException.class,
                () -> new EntityTypeEntry("user", "u.dat", 1, null));
    }
}
