package com.marksayson.deletionchecker.generator;

/**
 * Thrown when the generator's input is malformed — a line that is not a valid
 * {@code {"entityType": "...", "id": "..."}} object, or an identifier that fails the dataset's
 * constraints. The message names the offending line.
 */
public class InvalidInputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a description of what was wrong with the input
     */
    public InvalidInputException(final String message) {
        super(message);
    }
}
