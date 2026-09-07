package com.marksayson.deletionchecker;

import java.nio.charset.StandardCharsets;

/**
 * Converts identifier strings to the raw bytes used for storage and lookup, enforcing the dataset
 * format's identifier constraints in the process.
 *
 * <p>An identifier is valid when it is non-null, non-empty, encodes to at most
 * {@value #MAX_IDENTIFIER_BYTES} bytes in UTF-8, and contains no unpaired UTF-16 surrogate. A
 * surrogate is one half of the two-{@code char} pair Java uses to hold a code point above U+FFFF;
 * a half without its partner is unpaired and represents no character. Unpaired surrogates are
 * rejected rather than encoded: the UTF-8 encoder would substitute U+FFFD for them, so two distinct
 * caller identifiers could otherwise collide onto the same stored bytes.
 *
 * <p>No Unicode normalization is performed — comparison is exact-byte, so callers must supply
 * identifiers exactly as issued by the authoritative source.
 */
public final class IdentifierCodec {

    /** Maximum length, in UTF-8 encoded bytes, of a valid identifier. */
    public static final int MAX_IDENTIFIER_BYTES = 36;

    private IdentifierCodec() {
    }

    /**
     * Validates {@code identifier} and returns its UTF-8 encoding.
     *
     * @param identifier the identifier to encode
     * @return the UTF-8 bytes of {@code identifier}, never more than {@value #MAX_IDENTIFIER_BYTES}
     *     bytes long
     * @throws IllegalArgumentException if {@code identifier} is null, empty, contains an unpaired
     *     surrogate, or encodes to more than {@value #MAX_IDENTIFIER_BYTES} bytes
     */
    public static byte[] encode(final String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier must not be null");
        }
        if (identifier.isEmpty()) {
            throw new IllegalArgumentException("identifier must not be empty");
        }
        rejectUnpairedSurrogates(identifier);

        final byte[] bytes = identifier.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_IDENTIFIER_BYTES) {
            throw new IllegalArgumentException(
                    "identifier is " + bytes.length + " UTF-8 bytes, over the "
                            + MAX_IDENTIFIER_BYTES + "-byte maximum");
        }
        return bytes;
    }

    private static void rejectUnpairedSurrogates(final String identifier) {
        final int length = identifier.length();
        for (int i = 0; i < length; i++) {
            final char c = identifier.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == length || !Character.isLowSurrogate(identifier.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "identifier has an unpaired high surrogate at index " + i);
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "identifier has an unpaired low surrogate at index " + i);
            }
        }
    }
}
