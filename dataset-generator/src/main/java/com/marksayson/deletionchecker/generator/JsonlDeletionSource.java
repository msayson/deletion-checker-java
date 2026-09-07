package com.marksayson.deletionchecker.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Reads deletion records from a UTF-8 JSONL file — one
 * {@code {"entityType": "...", "id": "..."}} object per line (see {@link JsonlLine}). Blank lines
 * are ignored; any other malformed line throws {@link InvalidInputException} naming the line.
 */
public final class JsonlDeletionSource implements DeletionSource {

    private final Path path;

    /**
     * Creates a source over {@code path}.
     *
     * @param path the JSONL file to read
     */
    public JsonlDeletionSource(final Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    @Override
    public void forEach(final Consumer<DeletionRecord> consumer) throws IOException {
        Objects.requireNonNull(consumer, "consumer");
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    consumer.accept(JsonlLine.parse(line, lineNumber));
                }
            }
        }
    }
}
