package com.marksayson.deletionchecker.generator;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * A stream of {@link DeletionRecord}s for the generator to consume. Implementations pass records one
 * at a time so a large input never has to be held in memory at once.
 */
public interface DeletionSource {

    /**
     * Invokes {@code consumer} once per record, in input order.
     *
     * @param consumer receives each record
     * @throws IOException if the underlying input cannot be read
     * @throws InvalidInputException if the input is structurally malformed
     */
    void forEach(Consumer<DeletionRecord> consumer) throws IOException;
}
