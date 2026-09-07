package com.marksayson.deletionchecker.manifest;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestJsonTest {

    private static void rejects(final String json) {
        assertThrows(InvalidManifestException.class, () -> ManifestJson.parse(json), json);
    }

    @Test
    void parsesTheDesignSampleStructure() {
        final String json = """
                {
                  "formatVersion": 1,
                  "datasetVersion": "2026-09-06T17:00:00Z",
                  "generatorVersion": "3.2.1",
                  "entityTypes": [
                    { "entityType": "user", "fileName": "u.dat", "identifierCount": 4213000,
                      "checksum": "crc32c:9f3a1c7e" },
                    { "entityType": "order", "fileName": "o.dat", "identifierCount": 812044,
                      "checksum": "crc32c:2a7cd0e1" }
                  ],
                  "manifestChecksum": "sha256:3b1e7a9cd2f0"
                }
                """;
        final Object root = ManifestJson.parse(json);
        assertTrue(root instanceof Map<?, ?>);
        final Map<?, ?> map = (Map<?, ?>) root;
        assertEquals(1L, map.get("formatVersion"));
        assertEquals("2026-09-06T17:00:00Z", map.get("datasetVersion"));
        assertEquals(2, ((List<?>) map.get("entityTypes")).size());
        final Map<?, ?> first = (Map<?, ?>) ((List<?>) map.get("entityTypes")).get(0);
        assertEquals("user", first.get("entityType"));
        assertEquals(4213000L, first.get("identifierCount"));
    }

    @Test
    void parsesEmptyObjectAndArray() {
        assertEquals(Map.of(), ManifestJson.parse("{}"));
        assertEquals(List.of(), ManifestJson.parse("[]"));
    }

    @Test
    void parsesNestedStructureAndWhitespaceOfEveryKind() {
        final Object root = ManifestJson.parse(" \t\n\r{ \"a\" : [ 1 , -2 , 0 ] , \"b\" : \"x\" }\n");
        assertEquals(Map.of("a", List.of(1L, -2L, 0L), "b", "x"), root);
    }

    @Test
    void rejectsEmptyInput() {
        rejects("");
        rejects("   ");
    }

    @Test
    void rejectsTrailingContent() {
        rejects("{}x");
        rejects("[] []");
    }

    @Test
    void rejectsUnexpectedValueCharacters() {
        rejects("{\"a\": x}");
        rejects("{\"a\": true}");
        rejects("{\"a\": null}");
    }

    @Test
    void rejectsNestingPastTheDepthLimit() {
        final InvalidManifestException thrown = assertThrows(InvalidManifestException.class,
                () -> ManifestJson.parse("[[[[[1]]]]]")); // five arrays, one past the limit
        assertTrue(thrown.getMessage().contains("nesting"));
        rejects("[".repeat(100) + "]".repeat(100));
        rejects("{\"a\":".repeat(100) + "1" + "}".repeat(100));
    }

    @Test
    void acceptsNestingWithinTheDepthLimit() {
        assertEquals(
                List.of(List.of(List.of(1L))),
                ManifestJson.parse("[[[1]]]"));
    }

    @Test
    void rejectsUnterminatedContainers() {
        rejects("{");
        rejects("[");
        rejects("{\"a\":1");
        rejects("[1");
        rejects("{\"a\":");
        rejects("{\"a\":-");
        rejects("{\"a\":1,");
    }

    @Test
    void rejectsMissingStructuralPunctuation() {
        rejects("{\"a\" 1}");
        rejects("{\"a\":1 \"b\":2}");
        rejects("[1 2]");
    }

    @Test
    void rejectsNonStringKeys() {
        rejects("{1: 2}");
        rejects("{true: 2}");
    }

    @Test
    void rejectsDuplicateKeys() {
        rejects("{\"a\":1,\"a\":2}");
    }

    @Test
    void rejectsStringEscapesAndControlCharacters() {
        rejects("{\"a\":\"x\\ny\"}");
        rejects("{\"a\":\"x\\\"y\"}");
        rejects("{\"a\":\"line\nbreak\"}");
        rejects("{\"a\":\"unterminated}");
    }

    @Test
    void rejectsMalformedNumbers() {
        rejects("{\"a\":-}");
        rejects("{\"a\":01}");
        rejects("{\"a\":1.5}");
        rejects("{\"a\":1e3}");
        rejects("{\"a\":1E3}");
        rejects("{\"a\":1+2}");
        rejects("{\"a\":1-2}");
        rejects("{\"a\":99999999999999999999999}");
    }

    @Test
    void acceptsNegativeAndZeroIntegers() {
        assertEquals(Map.of("a", -7L, "b", 0L), ManifestJson.parse("{\"a\":-7,\"b\":0}"));
        assertEquals(Map.of("a", 0L), ManifestJson.parse("{\"a\":-0}"));
    }

    @Test
    void parsesABareValueAtTheRoot() {
        assertEquals(0L, ManifestJson.parse("0"));
        assertEquals(42L, ManifestJson.parse("42"));
        assertEquals(-5L, ManifestJson.parse("-5"));
        assertEquals("x", ManifestJson.parse("\"x\""));
    }
}
