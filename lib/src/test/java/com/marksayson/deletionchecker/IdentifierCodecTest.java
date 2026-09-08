package com.marksayson.deletionchecker;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentifierCodecTest {

    @Test
    void encodesAsciiToItsBytes() {
        assertArrayEquals(new byte[] {'1', '2', '3'}, IdentifierCodec.encode("123"));
    }

    @Test
    void encodesMultiByteCharacterAsUtf8() {
        assertArrayEquals(new byte[] {(byte) 0xC3, (byte) 0xA9}, IdentifierCodec.encode("é"));
    }

    @Test
    void encodesSupplementaryCharacterAsFourUtf8Bytes() {
        final byte[] expected = {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80};
        assertArrayEquals(expected, IdentifierCodec.encode("😀"));
    }

    @Test
    void acceptsCanonicalUuid() {
        final String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertArrayEquals(uuid.getBytes(StandardCharsets.UTF_8), IdentifierCodec.encode(uuid));
    }

    @Test
    void acceptsIdentifierOfExactlyMaxBytes() {
        final String maxLength = "a".repeat(IdentifierCodec.MAX_IDENTIFIER_BYTES);
        assertArrayEquals(
                maxLength.getBytes(StandardCharsets.UTF_8), IdentifierCodec.encode(maxLength));
    }

    @Test
    void rejectsIdentifierOverMaxBytes() {
        final String tooLong = "a".repeat(65);
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> IdentifierCodec.encode(tooLong));
        assertEquals("identifier is 65 UTF-8 bytes, over the 64-byte maximum", e.getMessage());
    }

    @Test
    void rejectsIdentifierOverMaxBytesCountingEncodedBytesNotCharacters() {
        // 17 supplementary characters = 34 chars but 68 UTF-8 bytes.
        final String thirtyFourChars = "😀".repeat(17);
        assertEquals(34, thirtyFourChars.length());
        assertThrows(IllegalArgumentException.class, () -> IdentifierCodec.encode(thirtyFourChars));
    }

    @Test
    void rejectsNull() {
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> IdentifierCodec.encode(null));
        assertEquals("identifier must not be null", e.getMessage());
    }

    @Test
    void rejectsEmpty() {
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> IdentifierCodec.encode(""));
        assertEquals("identifier must not be empty", e.getMessage());
    }

    @Test
    void rejectsLoneHighSurrogate() {
        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> IdentifierCodec.encode("a\uD800"));
        assertEquals("identifier has an unpaired high surrogate at index 1", e.getMessage());
    }

    @Test
    void rejectsHighSurrogateNotFollowedByLowSurrogate() {
        assertThrows(
                IllegalArgumentException.class, () -> IdentifierCodec.encode("\uD800x"));
    }

    @Test
    void rejectsLoneLowSurrogate() {
        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> IdentifierCodec.encode("\uDC00"));
        assertEquals("identifier has an unpaired low surrogate at index 0", e.getMessage());
    }

    @Test
    void rejectsLowSurrogateTrailingAValidPair() {
        final IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> IdentifierCodec.encode("😀\uDC00"));
        assertEquals("identifier has an unpaired low surrogate at index 2", e.getMessage());
    }

    @Test
    void acceptsValidSurrogatePairSurroundedByAscii() {
        assertDoesNotThrow(() -> IdentifierCodec.encode("a😀b"));
    }
}
