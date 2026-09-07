package com.marksayson.deletionchecker.manifest;

import com.marksayson.deletionchecker.checksum.Sha256;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Renders a manifest to the JSON written to disk: the canonical form (DESIGN: keys ascending, no
 * insignificant whitespace) with a trailing {@code manifestChecksum} field holding the SHA-256 of
 * the canonical form <em>without</em> that field.
 *
 * <p>Build-time only.
 */
public final class ManifestWriter {

    private ManifestWriter() {
    }

    /**
     * Returns the on-disk JSON for {@code manifest}.
     *
     * @param manifest the manifest to write
     * @return the JSON text, including the computed {@code manifestChecksum}
     */
    public static String write(final DatasetManifest manifest) {
        final String canonical = ManifestCanonicalizer.canonicalize(manifest);
        final String checksum = "sha256:" + HexFormat.of().formatHex(
                Sha256.of(canonical.getBytes(StandardCharsets.UTF_8)));
        // manifestChecksum sorts last, so it goes just before the closing brace.
        return canonical.substring(0, canonical.length() - 1)
                + ",\"manifestChecksum\":\"" + checksum + "\"}";
    }
}
