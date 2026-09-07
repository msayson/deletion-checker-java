package com.marksayson.deletionchecker.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonlLineTest {

    private static DeletionRecord parse(final String line) {
        return JsonlLine.parse(line, 1);
    }

    private static void rejects(final String line) {
        assertThrows(InvalidInputException.class, () -> JsonlLine.parse(line, 7), line);
    }

    @Test
    void parsesAWellFormedRecord() {
        final DeletionRecord record = parse("{\"entityType\": \"user\", \"id\": \"abc-123\"}");
        assertEquals("user", record.entityType());
        assertEquals("abc-123", record.id());
        assertEquals(1L, record.lineNumber());
    }

    @Test
    void keyOrderDoesNotMatter() {
        final DeletionRecord record = parse("{\"id\":\"x\",\"entityType\":\"order\"}");
        assertEquals("order", record.entityType());
        assertEquals("x", record.id());
    }

    @Test
    void toleratesInsignificantWhitespace() {
        assertEquals("u",
                parse(" \n\r\t{ \"entityType\"\t:\n\"u\" ,\r\"id\" : \"i\" }\n ").entityType());
    }

    @Test
    void honoursStringEscapes() {
        assertEquals("a\"b\\c/d\b\f\n\r\te",
                parse("{\"entityType\":\"t\",\"id\":\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\te\"}").id());
    }

    @Test
    void honoursUnicodeEscapesInEitherHexCaseAndSurrogatePairs() {
        assertEquals("\u00e9", parse("{\"entityType\":\"t\",\"id\":\"\\u00e9\"}").id()); // lowercase
        assertEquals("\uABCD", parse("{\"entityType\":\"t\",\"id\":\"\\uABCD\"}").id()); // uppercase
        assertEquals("\uD83D\uDE00", parse("{\"entityType\":\"t\",\"id\":\"\\uD83D\\uDE00\"}").id());
    }

    @Test
    void keepsRawUtf8AndAnUnpairedSurrogateFromAnEscape() {
        assertEquals("café", parse("{\"entityType\":\"t\",\"id\":\"café\"}").id());
        assertEquals("\uD800", parse("{\"entityType\":\"t\",\"id\":\"\\uD800\"}").id());
    }

    @Test
    void reportsTheLineNumberInErrors() {
        final InvalidInputException thrown = assertThrows(
                InvalidInputException.class, () -> JsonlLine.parse("not json", 42));
        assertEquals(true, thrown.getMessage().startsWith("line 42, column "));
    }

    @Test
    void rejectsStructuralProblems() {
        rejects("");
        rejects("   ");
        rejects("{");
        rejects("{\"entityType\":\"u\",\"id\":\"i\"");
        rejects("{\"entityType\":\"u\" \"id\":\"i\"}");
        rejects("{\"entityType\":\"u\",\"id\":\"i\"} trailing");
        rejects("[\"entityType\",\"u\"]");
        rejects("{\"entityType\":\"u\",\"id\":\"i\",}");
    }

    @Test
    void rejectsMissingDuplicateAndUnknownKeys() {
        rejects("{\"entityType\":\"u\"}");
        rejects("{\"id\":\"i\"}");
        rejects("{}");
        rejects("{\"entityType\":\"u\",\"entityType\":\"v\",\"id\":\"i\"}");
        rejects("{\"entityType\":\"u\",\"id\":\"i\",\"extra\":\"x\"}");
    }

    @Test
    void rejectsNonStringValuesAndKeys() {
        rejects("{\"entityType\":\"u\",\"id\":5}");
        rejects("{\"entityType\":\"u\",\"id\":true}");
        rejects("{\"entityType\":\"u\",\"id\":null}");
        rejects("{entityType:\"u\",\"id\":\"i\"}");
    }

    @Test
    void rejectsBadStringsAndEscapes() {
        rejects("{\"entityType\":\"u\",\"id\":\"unterminated}");
        rejects("{\"entityType\":\"u\",\"id\":\"bad\\xescape\"}");
        rejects("{\"entityType\":\"u\",\"id\":\"ends with a backslash\\");
        rejects("{\"entityType\":\"u\",\"id\":\"truncated \\u12");
        rejects("{\"entityType\":\"u\",\"id\":\"short \\u12\"}");
        rejects("{\"entityType\":\"u\",\"id\":\"nonhex \\uZZZZ\"}");   // letter below 'A'
        rejects("{\"entityType\":\"u\",\"id\":\"nonhex \\u00fg\"}");  // lowercase letter above 'f'
        rejects("{\"entityType\":\"u\",\"id\":\"raw\tcontrol\"}");
    }
}
