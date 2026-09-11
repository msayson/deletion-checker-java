/**
 * CRC32C and SHA-256 helpers that hash a region of a buffer in place, without copying it. Used by
 * {@link com.marksayson.deletionchecker.format} and {@link com.marksayson.deletionchecker.manifest}
 * to verify dataset integrity on load and to compute checksums on write.
 */
package com.marksayson.deletionchecker.checksum;
