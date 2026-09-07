package com.marksayson.deletionchecker.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeaderTest {

    private static ByteBuffer written(final Header header) {
        final ByteBuffer buffer = ByteBuffer.allocate(PackedFileFormat.HEADER_SIZE);
        header.writeTo(buffer);
        return buffer;
    }

    @Test
    void roundTripsThroughABuffer() {
        final Header original = new Header(1, "user", 4_213_000, 128, 32_914, 0xDEADBEEFL);
        assertEquals(original, Header.readFrom(written(original)));
    }

    @Test
    void acceptsEntityTypeOfExactlyTheMaximumLength() {
        final Header header = new Header(1, "e".repeat(64), 0, 1, 1, 0L);
        assertEquals(header, Header.readFrom(written(header)));
    }

    @Test
    void readsTheChecksumAsUnsigned() {
        final Header header = new Header(1, "user", 0, 1, 1, 0xFFFFFFFFL);
        assertEquals(0xFFFFFFFFL, Header.readFrom(written(header)).checksum());
    }

    @Test
    void writesEveryFieldLittleEndian() {
        final Header header =
                new Header(1, "ab", 0x01020304, 0x05060708, 0x090A0B0C, 0x0D0E0F10L);
        final byte[] bytes = written(header).array();

        assertArrayEquals(
                new byte[] {(byte) 0x89, 'D', 'C', 'S'}, Arrays.copyOfRange(bytes, 0, 4));
        assertArrayEquals(new byte[] {1, 0, 0, 0}, Arrays.copyOfRange(bytes, 4, 8));
        assertArrayEquals(new byte[] {2, 0, 0, 0}, Arrays.copyOfRange(bytes, 8, 12));
        assertEquals('a', bytes[12]);
        assertEquals('b', bytes[13]);
        assertEquals(0, bytes[14]);
        assertArrayEquals(
                new byte[] {0x04, 0x03, 0x02, 0x01}, Arrays.copyOfRange(bytes, 76, 80));
        assertArrayEquals(
                new byte[] {0x08, 0x07, 0x06, 0x05}, Arrays.copyOfRange(bytes, 80, 84));
        assertArrayEquals(
                new byte[] {0x0C, 0x0B, 0x0A, 0x09}, Arrays.copyOfRange(bytes, 84, 88));
        assertArrayEquals(
                new byte[] {0x10, 0x0F, 0x0E, 0x0D}, Arrays.copyOfRange(bytes, 88, 92));
    }

    @Test
    void writeLeavesTargetPositionUntouched() {
        final ByteBuffer buffer = ByteBuffer.allocate(PackedFileFormat.HEADER_SIZE);
        new Header(1, "user", 0, 1, 1, 0L).writeTo(buffer);
        assertEquals(0, buffer.position());
    }

    @Test
    void constructorRejectsNullEntityType() {
        assertThrows(
                NullPointerException.class, () -> new Header(1, null, 0, 1, 1, 0L));
    }

    @Test
    void constructorRejectsEmptyEntityType() {
        assertThrows(
                IllegalArgumentException.class, () -> new Header(1, "", 0, 1, 1, 0L));
    }

    @Test
    void constructorRejectsEntityTypeOverTheMaximumLength() {
        assertThrows(
                IllegalArgumentException.class, () -> new Header(1, "e".repeat(65), 0, 1, 1, 0L));
    }

    @Test
    void constructorRejectsNonAsciiEntityType() {
        assertThrows(
                IllegalArgumentException.class, () -> new Header(1, "usér", 0, 1, 1, 0L));
    }

    @Test
    void readRejectsBadMagicAsCorrupt() {
        final ByteBuffer buffer = written(new Header(1, "user", 0, 1, 1, 0L));
        buffer.put(1, (byte) 'X');
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsUnknownFormatVersionAsVersionMismatch() {
        final ByteBuffer buffer = written(new Header(1, "user", 0, 1, 1, 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.FORMAT_VERSION_OFFSET, 2);

        final UnsupportedFormatVersionException thrown = assertThrows(
                UnsupportedFormatVersionException.class, () -> Header.readFrom(buffer));
        assertEquals(2, thrown.found());
        assertEquals(1, thrown.supported());
    }

    @Test
    void readRejectsEntityTypeLengthOverTheMaximumAsCorrupt() {
        final ByteBuffer buffer = written(new Header(1, "user", 0, 1, 1, 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.ENTITY_TYPE_LENGTH_OFFSET, 65);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsZeroEntityTypeLengthAsCorrupt() {
        final ByteBuffer buffer = written(new Header(1, "user", 0, 1, 1, 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.ENTITY_TYPE_LENGTH_OFFSET, 0);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsNonAsciiEntityTypeBytesAsCorrupt() {
        final ByteBuffer buffer = written(new Header(1, "user", 0, 1, 1, 0L));
        buffer.put(PackedFileFormat.ENTITY_TYPE_OFFSET, (byte) 0x80);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }
}
