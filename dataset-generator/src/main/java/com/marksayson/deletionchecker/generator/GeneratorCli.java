package com.marksayson.deletionchecker.generator;

import com.marksayson.deletionchecker.format.PrefixIndex;
import com.marksayson.deletionchecker.manifest.DatasetManifest;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Command-line entry point for the dataset generator: reads a JSONL deletion feed and writes a
 * packed dataset directory.
 */
@Command(
        name = "dataset-generator",
        mixinStandardHelpOptions = true,
        version = "dataset-generator (format v" + DatasetManifest.SUPPORTED_FORMAT_VERSION + ")",
        description = "Packs a JSONL deletion feed into an immutable dataset directory.")
public final class GeneratorCli implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, required = true, paramLabel = "FILE",
            description = "JSONL feed: one {\"entityType\": \"...\", \"id\": \"...\"} object per line.")
    private Path input;

    @Option(names = {"-o", "--output"}, required = true, paramLabel = "DIR",
            description = "Directory for the packed files and manifest.json (created if absent).")
    private Path output;

    @Option(names = "--generator-version", required = true, paramLabel = "VERSION",
            description = "Semantic version of this generator build, recorded in the manifest.")
    private String generatorVersion;

    @Option(names = "--dataset-version", paramLabel = "ISO8601",
            description = "ISO-8601 timestamp identifying this run (default: now).")
    private String datasetVersion;

    @Option(names = "--bucket-size", paramLabel = "K",
            description = "Prefix-index bucket target size (default: ${DEFAULT-VALUE}).")
    private int bucketSize = PrefixIndex.DEFAULT_BUCKET_SIZE;

    @Override
    public Integer call() throws Exception {
        final GeneratorConfig config = new GeneratorConfig(
                generatorVersion,
                datasetVersion != null ? datasetVersion : Instant.now().toString(),
                bucketSize);

        Files.createDirectories(output);
        final DatasetManifest manifest =
                DatasetGenerator.generate(new JsonlDeletionSource(input), output, config);

        final PrintWriter out = spec.commandLine().getOut();
        out.printf("Wrote %d entity type(s) to %s (datasetVersion %s)%n",
                manifest.entityTypes().size(), output, manifest.datasetVersion());
        manifest.entityTypes().forEach(entry -> out.printf("  %-24s %,d ids  %s%n",
                entry.entityType(), entry.identifierCount(), entry.fileName()));
        return 0;
    }

    /**
     * Builds the configured {@link CommandLine} for this generator: malformed input and identifier
     * errors print a one-line message and exit {@code 2}; everything else uses picocli defaults.
     *
     * @return the command line
     */
    public static CommandLine commandLine() {
        return new CommandLine(new GeneratorCli())
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    if (ex instanceof InvalidInputException
                            || ex instanceof IllegalArgumentException) {
                        cmd.getErr().println(cmd.getColorScheme().errorText(ex.getMessage()));
                        return 2;
                    }
                    throw ex;
                });
    }

    /**
     * Runs the generator and exits with its status code.
     *
     * @param args the command-line arguments
     */
    public static void main(final String[] args) {
        System.exit(commandLine().execute(args));
    }
}
