package com.marksayson.deletionchecker.manifest;

/**
 * Thrown when the manifest's {@code formatVersion} is not the JSON schema version this build
 * understands — the artifact is too old or too new for this runtime. Distinct from an invalid
 * manifest ({@link InvalidManifestException}); the fail-fast behaviour is the same but the fix
 * differs.
 */
public class UnsupportedManifestVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int found;
    private final int supported;

    /**
     * Creates the exception.
     *
     * @param found the {@code formatVersion} read from the manifest
     * @param supported the {@code formatVersion} this build understands
     */
    public UnsupportedManifestVersionException(final int found, final int supported) {
        super("unsupported manifest format version " + found
                + "; this build reads version " + supported);
        this.found = found;
        this.supported = supported;
    }

    /**
     * Returns the {@code formatVersion} read from the manifest.
     *
     * @return the manifest's format version
     */
    public int found() {
        return found;
    }

    /**
     * Returns the {@code formatVersion} this build understands.
     *
     * @return the supported format version
     */
    public int supported() {
        return supported;
    }
}
