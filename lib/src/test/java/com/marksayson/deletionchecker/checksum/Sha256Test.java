package com.marksayson.deletionchecker.checksum;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sha256Test {

    private static String hexDigestOf(final String input) {
        return HexFormat.of().formatHex(Sha256.of(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void matchesFips180AbcVector() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hexDigestOf("abc"));
    }

    @Test
    void matchesEmptyInputVector() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hexDigestOf(""));
    }

    @Test
    void digestIsThirtyTwoBytes() {
        assertEquals(32, Sha256.of(new byte[] {1, 2, 3}).length);
    }
}
