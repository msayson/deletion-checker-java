package com.marksayson.deletionchecker.generator;

/**
 * Parses one line of the generator's JSONL input: a flat JSON object with exactly a string
 * {@code "entityType"} and a string {@code "id"}, e.g.
 * {@code {"entityType": "user", "id": "5f3c-..."}}.
 *
 * <p>Strict — every deviation throws {@link InvalidInputException} naming the line and column:
 * unknown, missing, or duplicate keys, non-string values, unquoted tokens
 * ({@code true} / numbers / {@code null}), unterminated strings, bad escapes, raw control
 * characters, and trailing content. String escapes are honoured ({@code \" \\ \/ \b \f \n \r \t}
 * and {@code \\uXXXX}) so identifiers written by an ASCII-escaping JSON emitter round-trip; a
 * {@code \\uXXXX} that yields an unpaired surrogate is left as-is for the identifier validation to
 * reject.
 */
final class JsonlLine {

    private final String text;
    private final long lineNumber;
    private int position;

    private JsonlLine(final String text, final long lineNumber) {
        this.text = text;
        this.lineNumber = lineNumber;
    }

    static DeletionRecord parse(final String line, final long lineNumber) {
        return new JsonlLine(line, lineNumber).readObject();
    }

    private DeletionRecord readObject() {
        skipWhitespace();
        expect('{');
        String entityType = null;
        String id = null;

        skipWhitespace();
        if (peek() == '}') {
            position++;
        } else {
            while (true) {
                skipWhitespace();
                final String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                final String value = readString();
                switch (key) {
                    case "entityType" -> entityType = requireFirst(key, entityType, value);
                    case "id" -> id = requireFirst(key, id, value);
                    default -> throw error("unexpected key '" + key + "'");
                }
                skipWhitespace();
                final char separator = next();
                if (separator == '}') {
                    break;
                }
                if (separator != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        skipWhitespace();
        if (position != text.length()) {
            throw error("unexpected trailing content");
        }
        if (entityType == null) {
            throw error("missing key 'entityType'");
        }
        if (id == null) {
            throw error("missing key 'id'");
        }
        return new DeletionRecord(entityType, id, lineNumber);
    }

    private String requireFirst(final String key, final String existing, final String value) {
        if (existing != null) {
            throw error("duplicate key '" + key + "'");
        }
        return value;
    }

    private String readString() {
        expect('"');
        final StringBuilder value = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            final char c = text.charAt(position++);
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\') {
                value.append(readEscape());
            } else if (c < 0x20) {
                throw error("unescaped control character");
            } else {
                value.append(c);
            }
        }
    }

    private char readEscape() {
        if (position >= text.length()) {
            throw error("unterminated escape sequence");
        }
        final char marker = text.charAt(position++);
        return switch (marker) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error("invalid escape '\\" + marker + "'");
        };
    }

    private char readUnicodeEscape() {
        if (position + 4 > text.length()) {
            throw error("truncated \\u escape");
        }
        int codeUnit = 0;
        for (int i = 0; i < 4; i++) {
            codeUnit = codeUnit * 16 + hexValue(text.charAt(position++));
        }
        return (char) codeUnit;
    }

    private int hexValue(final char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw error("invalid hex digit in \\u escape");
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end of line");
        }
        return text.charAt(position);
    }

    private char next() {
        final char c = peek();
        position++;
        return c;
    }

    private void expect(final char expected) {
        if (next() != expected) {
            throw error("expected '" + expected + "'");
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

    private InvalidInputException error(final String message) {
        return new InvalidInputException(
                "line " + lineNumber + ", column " + (position + 1) + ": " + message);
    }
}
