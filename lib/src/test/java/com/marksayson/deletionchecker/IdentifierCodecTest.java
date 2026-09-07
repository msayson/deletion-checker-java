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
    void acceptsIdentifierOfExactlyMaxBytes() {
        final String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(IdentifierCodec.MAX_IDENTIFIER_BYTES, uuid.length());
        assertArrayEquals(uuid.getBytes(StandardCharsets.UTF_8), IdentifierCodec.encode(uuid));
    }

    @Test
    void rejectsIdentifierOverMaxBytes() {
        final String tooLong = "123e4567-e89b-12d3-a456-426614174000x";
        final IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> IdentifierCodec.encode(tooLong));
        assertEquals("identifier is 37 UTF-8 bytes, over the 36-byte maximum", e.getMessage());
    }

    @Test
    void rejectsIdentifierOverMaxBytesCountingEncodedBytesNotCharacters() {
        // 10 supplementary characters = 20 chars but 40 UTF-8 bytes.
        final String twentyChars = "😀".repeat(10);
        assertEquals(20, twentyChars.length());
        assertThrows(IllegalArgumentException.class, () -> IdentifierCodec.encode(twentyChars));
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
