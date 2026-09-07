package com.marksayson.deletionchecker.checksum;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChecksumsTest {

    private static final int FIELD_LENGTH = 4;

    private static byte[] randomBytes(final int length) {
        final byte[] bytes = new byte[length];
        new Random(20260906L).nextBytes(bytes);
        return bytes;
    }

    private static long crcOfCopyWithFieldZeroed(
            final byte[] file, final int fieldOffset, final int fieldLength) {
        final byte[] copy = file.clone();
        Arrays.fill(copy, fieldOffset, fieldOffset + fieldLength, (byte) 0);
        final CRC32C crc = new CRC32C();
        crc.update(copy);
        return crc.getValue();
    }

    @Test
    void matchesHashingAModifiedCopyWhenFieldIsInTheMiddle() {
        final byte[] file = randomBytes(200);
        assertEquals(
                crcOfCopyWithFieldZeroed(file, 88, FIELD_LENGTH),
                Checksums.crc32cWithFieldZeroed(ByteBuffer.wrap(file), 88, FIELD_LENGTH));
    }

    @Test
    void matchesWhenFieldIsAtTheStart() {
        final byte[] file = randomBytes(64);
        assertEquals(
                crcOfCopyWithFieldZeroed(file, 0, FIELD_LENGTH),
                Checksums.crc32cWithFieldZeroed(ByteBuffer.wrap(file), 0, FIELD_LENGTH));
    }

    @Test
    void matchesWhenFieldIsAtTheEnd() {
        final byte[] file = randomBytes(64);
        assertEquals(
                crcOfCopyWithFieldZeroed(file, file.length - FIELD_LENGTH, FIELD_LENGTH),
                Checksums.crc32cWithFieldZeroed(
                        ByteBuffer.wrap(file), file.length - FIELD_LENGTH, FIELD_LENGTH));
    }

    @Test
    void treatsTheBufferLimitAsEndOfFile() {
        final byte[] backing = randomBytes(200);
        final ByteBuffer buffer = ByteBuffer.wrap(backing);
        buffer.limit(92);

        assertEquals(
                crcOfCopyWithFieldZeroed(Arrays.copyOf(backing, 92), 88, FIELD_LENGTH),
                Checksums.crc32cWithFieldZeroed(buffer, 88, FIELD_LENGTH));
    }

    @Test
    void leavesBufferPositionAndLimitUnchanged() {
        final ByteBuffer buffer = ByteBuffer.wrap(randomBytes(100));
        buffer.position(7);
        final int position = buffer.position();
        final int limit = buffer.limit();

        Checksums.crc32cWithFieldZeroed(buffer, 40, FIELD_LENGTH);

        assertEquals(position, buffer.position());
        assertEquals(limit, buffer.limit());
    }
}
