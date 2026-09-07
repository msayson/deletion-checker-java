package com.marksayson.deletionchecker.checksum;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Crc32cTest {

    /** The CRC-32/ISCSI catalogue check value: CRC32C of ASCII "123456789". */
    private static final long CHECK_VALUE = 0xE3069283L;

    private static final byte[] CHECK_INPUT = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheStandardCheckValue() {
        assertEquals(CHECK_VALUE, Crc32c.of(CHECK_INPUT));
    }

    @Test
    void emptyInputIsZero() {
        assertEquals(0L, Crc32c.of(new byte[0]));
    }

    @Test
    void resultIsUnsignedNotSignExtended() {
        // 0xE3069283 has bit 31 set; a bad implementation returning an int would sign-extend it.
        assertEquals(0xE3069283L, Crc32c.of(CHECK_INPUT));
        assertEquals(Crc32c.of(CHECK_INPUT) & 0xFFFFFFFFL, Crc32c.of(CHECK_INPUT));
    }

    @Test
    void bufferOverloadMatchesArrayOverload() {
        assertEquals(Crc32c.of(CHECK_INPUT), Crc32c.of(ByteBuffer.wrap(CHECK_INPUT)));
    }

    @Test
    void bufferOverloadChecksumsOnlyTheRemainingBytes() {
        final ByteBuffer padded = ByteBuffer.wrap(
                ("xx" + "123456789" + "yyy").getBytes(StandardCharsets.US_ASCII));
        padded.position(2).limit(11);
        assertEquals(CHECK_VALUE, Crc32c.of(padded));
    }

    @Test
    void bufferPositionAndLimitAreUntouched() {
        final ByteBuffer buffer = ByteBuffer.wrap(CHECK_INPUT);
        buffer.position(3);
        final int position = buffer.position();
        final int limit = buffer.limit();
        Crc32c.of(buffer);
        assertEquals(position, buffer.position());
        assertEquals(limit, buffer.limit());
    }
}
