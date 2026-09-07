package com.marksayson.deletionchecker.format;

/**
 * Thrown when a dataset file is structurally invalid or fails its integrity check — bad magic bytes,
 * a checksum mismatch, an entity-type mismatch, or an out-of-range header field. The file cannot be
 * trusted. This is distinct from a file whose format version this build simply does not recognize,
 * which is {@link UnsupportedFormatVersionException}.
 */
public class CorruptDatasetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a description of what was wrong with the file
     */
    public CorruptDatasetException(final String message) {
        super(message);
    }
}
