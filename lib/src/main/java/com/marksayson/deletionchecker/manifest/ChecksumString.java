package com.marksayson.deletionchecker.manifest;

/**
 * Recognizes a checksum written as an algorithm prefix followed by a fixed run of lowercase hex
 * digits — the {@code "crc32c:<8 hex>"} / {@code "sha256:<64 hex>"} form the manifest carries
 * (DESIGN §5.5). Shared by the entry-checksum and {@code manifestChecksum} shape checks so both
 * agree on what "well-formed" means.
 */
final class ChecksumString {

    private ChecksumString() {
    }

    /**
     * Returns whether {@code value} is exactly {@code prefix} followed by {@code hexDigits} lowercase
     * hex characters and nothing else.
     *
     * @param value the string to test
     * @param prefix the required algorithm prefix, e.g. {@code "sha256:"}
     * @param hexDigits the exact number of lowercase hex characters required after the prefix
     * @return whether {@code value} has that shape
     */
    static boolean hasShape(final String value, final String prefix, final int hexDigits) {
        if (value.length() != prefix.length() + hexDigits || !value.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < value.length(); i++) {
            final char digit = value.charAt(i);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) {
                return false;
            }
        }
        return true;
    }
}
