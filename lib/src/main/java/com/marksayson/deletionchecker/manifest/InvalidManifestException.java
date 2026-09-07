package com.marksayson.deletionchecker.manifest;

/**
 * Thrown when the dataset manifest is not valid JSON, does not match the expected schema, or fails
 * its {@code manifestChecksum}. The manifest cannot be trusted. This is distinct from a manifest
 * whose format version this build does not recognize, which is
 * {@link UnsupportedManifestVersionException}.
 */
public class InvalidManifestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a description of what was wrong with the manifest
     */
    public InvalidManifestException(final String message) {
        super(message);
    }
}
