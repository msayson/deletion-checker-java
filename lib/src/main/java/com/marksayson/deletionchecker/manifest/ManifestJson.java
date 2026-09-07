package com.marksayson.deletionchecker.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader scoped to the dataset manifest. It parses objects, arrays, quoted
 * strings <em>without</em> escape sequences, and integers, and rejects everything else — escape
 * sequences, floating-point numbers, {@code true} / {@code false} / {@code null}, duplicate keys,
 * and trailing content. The result is a tree of {@link Map}, {@link List}, {@link String}, and
 * {@link Long}. Any malformed input throws {@link InvalidManifestException} rather than a low-level
 * runtime exception.
 */
final class ManifestJson {

    private final String text;
    private int position;

    private ManifestJson(final String text) {
        this.text = text;
    }

    static Object parse(final String text) {
        final ManifestJson reader = new ManifestJson(text);
        reader.skipWhitespace();
        final Object value = reader.readValue();
        reader.skipWhitespace();
        if (reader.position != text.length()) {
            throw new InvalidManifestException(
                    "unexpected trailing content at position " + reader.position);
        }
        return value;
    }

    private Object readValue() {
        if (position >= text.length()) {
            throw new InvalidManifestException("unexpected end of manifest");
        }
        final char c = text.charAt(position);
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        if (c == '-' || isDigit(c)) {
            return readNumber();
        }
        throw new InvalidManifestException(
                "unexpected character '" + c + "' at position " + position);
    }

    private Map<String, Object> readObject() {
        position++; // opening '{' already matched by the caller
        final Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (currentChar() == '}') {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            final String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            if (object.put(key, readValue()) != null) {
                throw new InvalidManifestException("duplicate key '" + key + "'");
            }
            skipWhitespace();
            final char separator = take();
            if (separator == '}') {
                return object;
            }
            if (separator != ',') {
                throw new InvalidManifestException(
                        "expected ',' or '}' at position " + (position - 1));
            }
        }
    }

    private List<Object> readArray() {
        position++; // opening '[' already matched by the caller
        final List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (currentChar() == ']') {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            final char separator = take();
            if (separator == ']') {
                return array;
            }
            if (separator != ',') {
                throw new InvalidManifestException(
                        "expected ',' or ']' at position " + (position - 1));
            }
        }
    }

    private String readString() {
        if (position >= text.length() || text.charAt(position) != '"') {
            throw new InvalidManifestException("expected a string at position " + position);
        }
        position++;
        final int start = position;
        while (position < text.length()) {
            final char c = text.charAt(position);
            if (c == '"') {
                final String value = text.substring(start, position);
                position++;
                return value;
            }
            if (c == '\\') {
                throw new InvalidManifestException(
                        "escape sequences are not supported (position " + position + ")");
            }
            if (c < 0x20) {
                throw new InvalidManifestException(
                        "unescaped control character at position " + position);
            }
            position++;
        }
        throw new InvalidManifestException("unterminated string");
    }

    private Long readNumber() {
        final int start = position;
        if (text.charAt(position) == '-') {
            position++;
        }
        if (position >= text.length() || !isDigit(text.charAt(position))) {
            throw new InvalidManifestException("malformed number at position " + start);
        }
        if (text.charAt(position) == '0'
                && position + 1 < text.length() && isDigit(text.charAt(position + 1))) {
            throw new InvalidManifestException("number has a leading zero at position " + position);
        }
        while (position < text.length() && isDigit(text.charAt(position))) {
            position++;
        }
        if (position < text.length() && isNumberContinuation(text.charAt(position))) {
            throw new InvalidManifestException(
                    "only integers are allowed (position " + position + ")");
        }
        try {
            return Long.parseLong(text, start, position, 10);
        } catch (final NumberFormatException e) {
            throw new InvalidManifestException("number out of range at position " + start);
        }
    }

    private char currentChar() {
        if (position >= text.length()) {
            throw new InvalidManifestException("unexpected end of manifest");
        }
        return text.charAt(position);
    }

    private char take() {
        final char c = currentChar();
        position++;
        return c;
    }

    private void expect(final char expected) {
        if (take() != expected) {
            throw new InvalidManifestException(
                    "expected '" + expected + "' at position " + (position - 1));
        }
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            final char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            position++;
        }
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNumberContinuation(final char c) {
        return c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }
}
