package com.marksayson.deletionchecker.manifest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityTypeEntryTest {

    private static final String VALID_CHECKSUM = "crc32c:0011eeff";

    private static void rejects(final String entityType, final String fileName,
            final long identifierCount, final String checksum) {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityTypeEntry(entityType, fileName, identifierCount, checksum));
    }

    @Test
    void acceptsAValidEntry() {
        final EntityTypeEntry entry = new EntityTypeEntry("user", "u.dat", 0, VALID_CHECKSUM);
        assertEquals("user", entry.entityType());
        assertEquals(0L, entry.identifierCount());
    }

    @Test
    void crc32cReferenceRendersEightLowercaseHexDigitsAndRoundTrips() {
        assertEquals("crc32c:0000000a", EntityTypeEntry.crc32cReference(0x0AL));
        assertEquals("crc32c:deadbeef", EntityTypeEntry.crc32cReference(0xDEADBEEFL));

        final String reference = EntityTypeEntry.crc32cReference(0xE3069283L);
        assertEquals(reference, new EntityTypeEntry("u", "u.dat", 1, reference).checksum());
    }

    @Test
    void rejectsANegativeIdentifierCount() {
        rejects("user", "u.dat", -1, VALID_CHECKSUM);
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new EntityTypeEntry(null, "u.dat", 1, VALID_CHECKSUM));
        assertThrows(NullPointerException.class,
                () -> new EntityTypeEntry("user", null, 1, VALID_CHECKSUM));
        assertThrows(NullPointerException.class,
                () -> new EntityTypeEntry("user", "u.dat", 1, null));
    }

    @Test
    void rejectsAnEmptyOrOversizedEntityType() {
        rejects("", "u.dat", 1, VALID_CHECKSUM);
        rejects("x".repeat(EntityTypeEntry.MAX_ENTITY_TYPE_LENGTH + 1), "u.dat", 1, VALID_CHECKSUM);
    }

    @Test
    void acceptsAnEntityTypeAtTheLengthLimit() {
        final String name = "x".repeat(EntityTypeEntry.MAX_ENTITY_TYPE_LENGTH);
        assertEquals(name, new EntityTypeEntry(name, "u.dat", 1, VALID_CHECKSUM).entityType());
    }

    @Test
    void rejectsANonAsciiEntityType() {
        rejects("usér", "u.dat", 1, VALID_CHECKSUM);
    }

    @Test
    void rejectsAFileNameThatIsNotABareFilename() {
        rejects("user", "", 1, VALID_CHECKSUM);
        rejects("user", "   ", 1, VALID_CHECKSUM);
        rejects("user", "sub/u.dat", 1, VALID_CHECKSUM);
        rejects("user", "sub\\u.dat", 1, VALID_CHECKSUM);
        rejects("user", "/abs/u.dat", 1, VALID_CHECKSUM);
        rejects("user", "..", 1, VALID_CHECKSUM);
        rejects("user", ".", 1, VALID_CHECKSUM);
    }

    @Test
    void rejectsAChecksumNotFormattedAsCrc32cHex() {
        rejects("user", "u.dat", 1, "0011eeff");           // no prefix
        rejects("user", "u.dat", 1, "crc32c:0011eef");     // too short
        rejects("user", "u.dat", 1, "crc32c:0011eeffa");   // too long
        rejects("user", "u.dat", 1, "crc32c:0011EEFF");    // uppercase hex
        rejects("user", "u.dat", 1, "crc32c:0011eefg");    // 'g' is past 'f'
        rejects("user", "u.dat", 1, "crc32c:0011ee.f");    // '.' is below '0'
        rejects("user", "u.dat", 1, "sha256:0011eeff");    // wrong algorithm prefix
    }
}
