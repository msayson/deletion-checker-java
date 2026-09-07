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

    private static Header v1(final String entityType, final long checksum) {
        return new Header(1, entityType, 0, 1, 1, checksum, 0);
    }

    @Test
    void roundTripsAV1Header() {
        final Header original = new Header(1, "user", 4_213_000, 128, 32_914, 0xDEADBEEFL, 0);
        assertEquals(original, Header.readFrom(written(original)));
        assertEquals(PackedFileFormat.HEADER_SIZE_V1, original.byteSize());
    }

    @Test
    void roundTripsAV2HeaderWithBloomBlockCount() {
        final Header original = new Header(2, "user", 1_000_000, 128, 7813, 0x0BADF00DL, 12_345);
        final Header read = Header.readFrom(written(original));
        assertEquals(original, read);
        assertEquals(12_345, read.bloomBlockCount());
        assertEquals(PackedFileFormat.HEADER_SIZE, original.byteSize());
    }

    @Test
    void aV1HeaderReadsBloomBlockCountAsZeroRegardlessOfBytesPastOffset92() {
        final ByteBuffer buffer = written(new Header(1, "user", 5, 1, 1, 0L, 0));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.BLOOM_BLOCK_COUNT_OFFSET, 999);
        assertEquals(0, Header.readFrom(buffer).bloomBlockCount());
    }

    @Test
    void acceptsEntityTypeOfExactlyTheMaximumLength() {
        final Header header = new Header(2, "e".repeat(64), 0, 1, 1, 0L, 0);
        assertEquals(header, Header.readFrom(written(header)));
    }

    @Test
    void readsTheChecksumAsUnsigned() {
        assertEquals(0xFFFFFFFFL, Header.readFrom(written(v1("user", 0xFFFFFFFFL))).checksum());
    }

    @Test
    void writesEveryFieldLittleEndian() {
        final Header header =
                new Header(2, "ab", 0x01020304, 0x05060708, 0x090A0B0C, 0x0D0E0F10L, 0x11121314);
        final byte[] bytes = written(header).array();

        assertArrayEquals(
                new byte[] {(byte) 0x89, 'D', 'C', 'S'}, Arrays.copyOfRange(bytes, 0, 4));
        assertArrayEquals(new byte[] {2, 0, 0, 0}, Arrays.copyOfRange(bytes, 4, 8));
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
        assertArrayEquals(
                new byte[] {0x14, 0x13, 0x12, 0x11}, Arrays.copyOfRange(bytes, 92, 96));
    }

    @Test
    void aV1HeaderWriteLeavesOffset92To95Untouched() {
        final ByteBuffer buffer = ByteBuffer.allocate(PackedFileFormat.HEADER_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.BLOOM_BLOCK_COUNT_OFFSET, 0x7F);
        new Header(1, "user", 0, 1, 1, 0L, 0).writeTo(buffer);
        assertEquals(0x7F, buffer.getInt(PackedFileFormat.BLOOM_BLOCK_COUNT_OFFSET));
    }

    @Test
    void writeLeavesTargetPositionUntouched() {
        final ByteBuffer buffer = ByteBuffer.allocate(PackedFileFormat.HEADER_SIZE);
        v1("user", 0L).writeTo(buffer);
        assertEquals(0, buffer.position());
    }

    @Test
    void constructorRejectsNullEntityType() {
        assertThrows(NullPointerException.class, () -> new Header(2, null, 0, 1, 1, 0L, 0));
    }

    @Test
    void constructorRejectsEmptyEntityType() {
        assertThrows(IllegalArgumentException.class, () -> new Header(2, "", 0, 1, 1, 0L, 0));
    }

    @Test
    void constructorRejectsEntityTypeOverTheMaximumLength() {
        assertThrows(
                IllegalArgumentException.class, () -> new Header(2, "e".repeat(65), 0, 1, 1, 0L, 0));
    }

    @Test
    void constructorRejectsNonAsciiEntityType() {
        assertThrows(IllegalArgumentException.class, () -> new Header(2, "usér", 0, 1, 1, 0L, 0));
    }

    @Test
    void constructorRejectsNegativeBloomBlockCount() {
        assertThrows(IllegalArgumentException.class, () -> new Header(2, "user", 0, 1, 1, 0L, -1));
    }

    @Test
    void readRejectsBadMagicAsCorrupt() {
        final ByteBuffer buffer = written(v1("user", 0L));
        buffer.put(1, (byte) 'X');
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readAcceptsBothV1AndV2() {
        assertEquals(1, Header.readFrom(written(v1("user", 0L))).formatVersion());
        assertEquals(2,
                Header.readFrom(written(new Header(2, "user", 0, 1, 1, 0L, 0))).formatVersion());
    }

    @Test
    void readRejectsAFutureFormatVersionAsVersionMismatch() {
        final ByteBuffer buffer = written(v1("user", 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.FORMAT_VERSION_OFFSET, 3);

        final UnsupportedFormatVersionException thrown = assertThrows(
                UnsupportedFormatVersionException.class, () -> Header.readFrom(buffer));
        assertEquals(3, thrown.found());
        assertEquals(2, thrown.supported());
    }

    @Test
    void readRejectsFormatVersionZeroAsVersionMismatch() {
        final ByteBuffer buffer = written(v1("user", 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.FORMAT_VERSION_OFFSET, 0);
        assertThrows(UnsupportedFormatVersionException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsANegativeBloomBlockCountAsCorrupt() {
        final ByteBuffer buffer = written(new Header(2, "user", 0, 1, 1, 0L, 0));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.BLOOM_BLOCK_COUNT_OFFSET, -1);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsEntityTypeLengthOverTheMaximumAsCorrupt() {
        final ByteBuffer buffer = written(v1("user", 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.ENTITY_TYPE_LENGTH_OFFSET, 65);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsZeroEntityTypeLengthAsCorrupt() {
        final ByteBuffer buffer = written(v1("user", 0L));
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(PackedFileFormat.ENTITY_TYPE_LENGTH_OFFSET, 0);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }

    @Test
    void readRejectsNonAsciiEntityTypeBytesAsCorrupt() {
        final ByteBuffer buffer = written(v1("user", 0L));
        buffer.put(PackedFileFormat.ENTITY_TYPE_OFFSET, (byte) 0x80);
        assertThrows(CorruptDatasetException.class, () -> Header.readFrom(buffer));
    }
}
