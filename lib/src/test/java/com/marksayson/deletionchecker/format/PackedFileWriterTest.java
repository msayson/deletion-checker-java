package com.marksayson.deletionchecker.format;

import com.marksayson.deletionchecker.checksum.Checksums;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedFileWriterTest {

    private static List<byte[]> ids(final String... items) {
        final List<byte[]> list = new ArrayList<>(items.length);
        for (final String item : items) {
            list.add(item.getBytes(StandardCharsets.UTF_8));
        }
        return list;
    }

    private static List<byte[]> sequentialIds(final int count) {
        final List<byte[]> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(String.format("%08d", i).getBytes(StandardCharsets.US_ASCII));
        }
        return list;
    }

    private static long storedChecksum(final byte[] file) {
        return Checksums.crc32cWithFieldZeroed(
                ByteBuffer.wrap(file),
                PackedFileFormat.CHECKSUM_OFFSET,
                PackedFileFormat.CHECKSUM_LENGTH);
    }

    /** Parses a packed file the long way — not via the runtime reader, which does not exist yet. */
    private static Parsed parse(final byte[] file) {
        final ByteBuffer buffer = ByteBuffer.wrap(file).order(PackedFileFormat.BYTE_ORDER);
        final Header header = Header.readFrom(buffer);
        final int buckets = header.bucketCount();
        final int count = header.identifierCount();
        int pos = PackedFileFormat.HEADER_SIZE;

        final int[] startIndex = new int[buckets + 1];
        for (int i = 0; i <= buckets; i++) {
            startIndex[i] = buffer.getInt(pos);
            pos += Integer.BYTES;
        }
        final int[] separatorOffset = new int[buckets + 1];
        for (int i = 0; i <= buckets; i++) {
            separatorOffset[i] = buffer.getInt(pos);
            pos += Integer.BYTES;
        }
        final byte[] separatorData = new byte[separatorOffset[buckets]];
        buffer.get(pos, separatorData);
        pos += separatorData.length;

        final int[] identifierOffset = new int[count + 1];
        for (int i = 0; i <= count; i++) {
            identifierOffset[i] = buffer.getInt(pos);
            pos += Integer.BYTES;
        }
        final byte[] identifierData = new byte[identifierOffset[count]];
        buffer.get(pos, identifierData);
        pos += identifierData.length;

        return new Parsed(
                header, startIndex, separatorOffset, separatorData, identifierOffset, identifierData, pos);
    }

    private record Parsed(
            Header header,
            int[] startIndex,
            int[] separatorOffset,
            byte[] separatorData,
            int[] identifierOffset,
            byte[] identifierData,
            int totalBytes) {
    }

    @Test
    void multiBucketFileHasEverySectionCorrect() {
        final List<byte[]> identifiers = ids("A", "B", "C", "D", "E", "F", "G", "H", "I");
        final byte[] file = PackedFileWriter.write("user", identifiers, 3);
        final Parsed parsed = parse(file);

        assertEquals(1, parsed.header().formatVersion());
        assertEquals("user", parsed.header().entityType());
        assertEquals(9, parsed.header().identifierCount());
        assertEquals(3, parsed.header().bucketSize());
        assertEquals(3, parsed.header().bucketCount());

        final PrefixIndex expected = PrefixIndex.build(identifiers, 3);
        assertArrayEquals(expected.startIndex(), parsed.startIndex());
        assertArrayEquals(expected.separatorOffset(), parsed.separatorOffset());
        assertArrayEquals(expected.separatorData(), parsed.separatorData());

        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, parsed.identifierOffset());
        assertArrayEquals("ABCDEFGHI".getBytes(StandardCharsets.UTF_8), parsed.identifierData());

        assertEquals(file.length, parsed.totalBytes(), "no trailing bytes");
        assertEquals(parsed.header().checksum(), storedChecksum(file), "checksum self-verifies");
    }

    @Test
    void identifierOffsetsAreStrictlyMonotonicAndReconstructEachIdentifier() {
        final List<byte[]> identifiers = ids("aa", "bbb", "c", "dddd");
        final Parsed parsed = parse(PackedFileWriter.write("t", identifiers, 128));

        for (int i = 0; i < identifiers.size(); i++) {
            assertTrue(parsed.identifierOffset()[i] < parsed.identifierOffset()[i + 1]);
            final byte[] slice = Arrays.copyOfRange(
                    parsed.identifierData(),
                    parsed.identifierOffset()[i],
                    parsed.identifierOffset()[i + 1]);
            assertArrayEquals(identifiers.get(i), slice);
        }
    }

    @Test
    void prefixIndexBlocksAreContiguousInStartIndexThenOffsetThenData() {
        final byte[] file = PackedFileWriter.write("x", ids("A", "B", "C", "D", "E", "F", "G", "H", "I"), 3);
        final int buckets = 3;
        final int afterStartIndex = PackedFileFormat.HEADER_SIZE + Integer.BYTES * (buckets + 1);
        final int afterSeparatorOffset = afterStartIndex + Integer.BYTES * (buckets + 1);

        // The separator bytes A, D, G land immediately after both int tables, not interleaved.
        assertArrayEquals(
                "ADG".getBytes(StandardCharsets.UTF_8),
                Arrays.copyOfRange(file, afterSeparatorOffset, afterSeparatorOffset + 3));
    }

    @Test
    void emptyDatasetProducesAWellFormedFile() {
        final byte[] file = PackedFileWriter.write("empty", ids(), 128);
        final Parsed parsed = parse(file);

        assertEquals(0, parsed.header().identifierCount());
        assertEquals(1, parsed.header().bucketCount());
        assertArrayEquals(new int[] {0, 0}, parsed.startIndex());
        assertArrayEquals(new int[] {0, 0}, parsed.separatorOffset());
        assertEquals(0, parsed.separatorData().length);
        assertArrayEquals(new int[] {0}, parsed.identifierOffset());
        assertEquals(0, parsed.identifierData().length);
        assertEquals(PackedFileFormat.HEADER_SIZE + 8 + 8 + 4, file.length);
        assertEquals(file.length, parsed.totalBytes());
        assertEquals(parsed.header().checksum(), storedChecksum(file));
    }

    @Test
    void singleIdentifierFile() {
        final byte[] file = PackedFileWriter.write("s", ids("only"), 128);
        final Parsed parsed = parse(file);

        assertEquals(1, parsed.header().identifierCount());
        assertArrayEquals(new int[] {0, 1}, parsed.startIndex());
        assertArrayEquals("only".getBytes(StandardCharsets.UTF_8), parsed.separatorData());
        assertArrayEquals(new int[] {0, 4}, parsed.identifierOffset());
        assertArrayEquals("only".getBytes(StandardCharsets.UTF_8), parsed.identifierData());
        assertEquals(file.length, parsed.totalBytes());
        assertEquals(parsed.header().checksum(), storedChecksum(file));
    }

    @Test
    void defaultBucketSizeOverloadMatchesTheExplicitDefault() {
        final List<byte[]> identifiers = sequentialIds(300);
        assertArrayEquals(
                PackedFileWriter.write("x", identifiers, PrefixIndex.DEFAULT_BUCKET_SIZE),
                PackedFileWriter.write("x", identifiers));
    }

    @Test
    void rejectsUnsortedIdentifiers() {
        assertThrows(
                IllegalArgumentException.class, () -> PackedFileWriter.write("x", ids("b", "a"), 128));
    }

    @Test
    void rejectsDuplicateIdentifiers() {
        assertThrows(
                IllegalArgumentException.class, () -> PackedFileWriter.write("x", ids("a", "a"), 128));
    }

    @Test
    void rejectsInvalidEntityType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PackedFileWriter.write("e".repeat(65), ids("a"), 128));
    }

    @Test
    void rejectsInvalidBucketSize() {
        assertThrows(
                IllegalArgumentException.class, () -> PackedFileWriter.write("x", ids("a"), 0));
    }
}
