package com.marksayson.deletionchecker.format;

/**
 * Thrown when a dataset file's format version is not the one this build understands — the artifact
 * is too old or too new for this runtime. This is distinct from a corrupted file
 * ({@link CorruptDatasetException}); the fail-fast behaviour is the same but the cause and the fix
 * differ.
 */
public class UnsupportedFormatVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int found;
    private final int supported;

    /**
     * Creates the exception.
     *
     * @param found the format version read from the file
     * @param supported the format version this build understands
     */
    public UnsupportedFormatVersionException(final int found, final int supported) {
        super("unsupported format version " + found + "; this build reads version " + supported);
        this.found = found;
        this.supported = supported;
    }

    /**
     * Returns the format version read from the file.
     *
     * @return the file's format version
     */
    public int found() {
        return found;
    }

    /**
     * Returns the format version this build understands.
     *
     * @return the supported format version
     */
    public int supported() {
        return supported;
    }
}
