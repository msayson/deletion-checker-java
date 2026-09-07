package com.marksayson.deletionchecker.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonlDeletionSourceTest {

    @TempDir
    private Path tempDir;

    private List<DeletionRecord> readAll(final String content) throws IOException {
        final Path file = tempDir.resolve("feed.jsonl");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        final List<DeletionRecord> records = new ArrayList<>();
        new JsonlDeletionSource(file).forEach(records::add);
        return records;
    }

    @Test
    void readsEveryRecordInOrderSkippingBlankLines() throws IOException {
        final List<DeletionRecord> records = readAll("""
                {"entityType": "user", "id": "u1"}

                {"entityType": "order", "id": "o1"}
                   \s
                {"entityType": "user", "id": "u2"}
                """);

        assertEquals(List.of("u1", "o1", "u2"), records.stream().map(DeletionRecord::id).toList());
        assertEquals(1L, records.get(0).lineNumber());
        assertEquals(3L, records.get(1).lineNumber());
        assertEquals(5L, records.get(2).lineNumber());
    }

    @Test
    void anEmptyFileYieldsNoRecords() throws IOException {
        assertEquals(List.of(), readAll(""));
    }

    @Test
    void aMalformedLineFailsWithItsLineNumber() throws IOException {
        final Path file = tempDir.resolve("bad.jsonl");
        Files.writeString(file, """
                {"entityType": "user", "id": "u1"}
                {"entityType": "user"}
                """);

        final InvalidInputException thrown = assertThrows(InvalidInputException.class,
                () -> new JsonlDeletionSource(file).forEach(record -> { }));
        assertEquals(true, thrown.getMessage().startsWith("line 2, "));
    }

    @Test
    void missingFileThrowsIoException() {
        assertThrows(IOException.class, () -> new JsonlDeletionSource(tempDir.resolve("absent"))
                .forEach(record -> { }));
    }
}
