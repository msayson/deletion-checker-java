package com.marksayson.deletionchecker.manifest;

import java.util.HexFormat;
import java.util.Objects;

/**
 * One entity type's entry in the dataset manifest.
 *
 * @param entityType the entity type name; 1 to {@value #MAX_ENTITY_TYPE_LENGTH} ASCII characters,
 *     matching the entity type's packed-file header
 * @param fileName the packed file's name — a bare filename with no directory separators, resolved
 *     relative to the dataset directory
 * @param identifierCount the number of unique identifiers the file holds; never negative
 * @param checksum the file's expected checksum, formatted as {@code "crc32c:"} followed by 8
 *     lowercase hex digits
 */
public record EntityTypeEntry(
        String entityType, String fileName, long identifierCount, String checksum) {

    /** Maximum length, in ASCII characters, of {@link #entityType}. */
    public static final int MAX_ENTITY_TYPE_LENGTH = 64;

    private static final String CHECKSUM_PREFIX = "crc32c:";
    private static final int CHECKSUM_HEX_LENGTH = 8;

    /**
     * Renders a CRC32C value as a manifest {@code checksum} reference: {@code "crc32c:"} followed by
     * the value's low 32 bits as 8 lowercase hex digits. This is the exact string
     * {@link #checksum()} is validated against and that {@code DeletionChecker} compares each loaded
     * file's checksum to.
     *
     * @param crc32c the CRC32C, as an unsigned 32-bit value in the low bits
     * @return the {@code checksum} string for a manifest entry
     */
    public static String crc32cReference(final long crc32c) {
        return CHECKSUM_PREFIX + HexFormat.of().toHexDigits((int) crc32c);
    }

    /** Validates the entry. */
    public EntityTypeEntry {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(checksum, "checksum");
        requireAsciiEntityType(entityType);
        requireBareFileName(fileName);
        requireCrc32cChecksum(checksum);
        if (identifierCount < 0) {
            throw new IllegalArgumentException(
                    "identifierCount must not be negative: " + identifierCount);
        }
    }

    private static void requireAsciiEntityType(final String entityType) {
        if (entityType.isEmpty() || entityType.length() > MAX_ENTITY_TYPE_LENGTH) {
            throw new IllegalArgumentException(
                    "entityType must be 1 to " + MAX_ENTITY_TYPE_LENGTH + " characters: '"
                            + entityType + "'");
        }
        for (int i = 0; i < entityType.length(); i++) {
            if (entityType.charAt(i) > 0x7F) {
                throw new IllegalArgumentException(
                        "entityType must be ASCII; non-ASCII character at index " + i);
            }
        }
    }

    private static void requireBareFileName(final String fileName) {
        if (fileName.isBlank()
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || ".".equals(fileName)
                || "..".equals(fileName)) {
            throw new IllegalArgumentException(
                    "fileName must be a bare filename with no directory separators: '"
                            + fileName + "'");
        }
    }

    private static void requireCrc32cChecksum(final String checksum) {
        if (!ChecksumString.hasShape(checksum, CHECKSUM_PREFIX, CHECKSUM_HEX_LENGTH)) {
            throw new IllegalArgumentException(
                    "checksum must be \"" + CHECKSUM_PREFIX + "\" followed by " + CHECKSUM_HEX_LENGTH
                            + " lowercase hex digits: '" + checksum + "'");
        }
    }
}
