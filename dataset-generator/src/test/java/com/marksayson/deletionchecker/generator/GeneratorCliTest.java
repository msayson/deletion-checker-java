package com.marksayson.deletionchecker.generator;

import com.marksayson.deletionchecker.DeletionChecker;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratorCliTest {

    @TempDir
    private Path tempDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(final String... args) {
        final CommandLine cli = GeneratorCli.commandLine();
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        return cli.execute(args);
    }

    private Path feed(final String content) throws IOException {
        final Path path = tempDir.resolve("feed.jsonl");
        Files.writeString(path, content);
        return path;
    }

    @Test
    void generatesADatasetIntoANewDirectory() throws IOException {
        final Path input = feed("""
                {"entityType": "user", "id": "u1"}
                {"entityType": "order", "id": "o1"}
                """);
        final Path output = tempDir.resolve("out"); // does not exist yet

        final int code = run(
                "--input", input.toString(),
                "--output", output.toString(),
                "--generator-version", "2.0.0",
                "--dataset-version", "2026-09-06T17:00:00Z",
                "--bucket-size", "8");

        assertEquals(0, code, err.toString());
        assertTrue(out.toString().contains("Wrote 2 entity type(s)"));

        final DeletionChecker checker = DeletionChecker.load(output, Set.of("user", "order"));
        assertTrue(checker.isDeleted("user", "u1"));
    }

    @Test
    void defaultsDatasetVersionAndBucketSize() throws IOException {
        final int code = run(
                "-i", feed("{\"entityType\": \"user\", \"id\": \"u1\"}\n").toString(),
                "-o", tempDir.resolve("out").toString(),
                "--generator-version", "1.0.0");

        assertEquals(0, code, err.toString());
    }

    @Test
    void missingRequiredOptionIsAUsageError() throws IOException {
        final int code = run("--input", feed("").toString(), "--output", tempDir.toString());
        assertEquals(CommandLine.ExitCode.USAGE, code);
        assertTrue(err.toString().contains("--generator-version"));
    }

    @Test
    void helpExitsZero() {
        assertEquals(0, run("--help"));
        assertTrue(out.toString().contains("Packs a JSONL deletion feed"));
    }

    @Test
    void aMalformedFeedLineExitsTwoWithAOneLineMessage() throws IOException {
        final int code = run(
                "-i", feed("{\"entityType\": \"user\"}\n").toString(),
                "-o", tempDir.resolve("out").toString(),
                "--generator-version", "1.0.0");

        assertEquals(2, code);
        assertTrue(err.toString().contains("line 1"));
        assertTrue(err.toString().contains("missing key 'id'"));
    }

    @Test
    void anUnexpectedIoFailurePropagatesAsExitOne() throws IOException {
        final Path outputIsAFile = tempDir.resolve("not-a-dir");
        Files.writeString(outputIsAFile, "blocking file");

        final int code = run(
                "-i", feed("{\"entityType\": \"user\", \"id\": \"u1\"}\n").toString(),
                "-o", outputIsAFile.toString(),
                "--generator-version", "1.0.0");

        assertEquals(CommandLine.ExitCode.SOFTWARE, code);
    }

    @Test
    void anInvalidGeneratorVersionExitsTwo() throws IOException {
        final int code = run(
                "-i", feed("{\"entityType\": \"user\", \"id\": \"u1\"}\n").toString(),
                "-o", tempDir.resolve("out").toString(),
                "--generator-version", "nightly");

        assertEquals(2, code);
        assertTrue(err.toString().contains("must begin with a digit"));
    }
}
