package com.marksayson.deletionchecker.format;

import com.marksayson.deletionchecker.checksum.Checksums;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackedDeletionSetTest {

    @TempDir
    private Path tempDir;

    private Path writeFile(final String entityType, final List<byte[]> identifiers, final int bucketSize)
            throws IOException {
        final Path path = tempDir.resolve(entityType + ".dat");
        Files.write(path, PackedFileWriter.write(entityType, identifiers, bucketSize));
        return path;
    }

    /** A v1 file (92-byte header, no Bloom section) rebuilt from a v2 file, for back-compat tests. */
    private Path writeV1File(final String entityType, final List<byte[]> identifiers)
            throws IOException {
        final byte[] v2 = PackedFileWriter.write(entityType, identifiers, 4, 1.0); // no Bloom section
        final byte[] v1 = new byte[v2.length - 4];
        System.arraycopy(v2, 0, v1, 0, PackedFileFormat.HEADER_SIZE_V1);            // header up to 92
        System.arraycopy(v2, PackedFileFormat.HEADER_SIZE, v1, PackedFileFormat.HEADER_SIZE_V1,
                v2.length - PackedFileFormat.HEADER_SIZE);                          // sections
        final ByteBuffer buffer = ByteBuffer.wrap(v1).order(PackedFileFormat.BYTE_ORDER);
        buffer.putInt(PackedFileFormat.FORMAT_VERSION_OFFSET, 1);
        buffer.putInt(PackedFileFormat.CHECKSUM_OFFSET, 0);
        final long crc = Checksums.crc32cWithFieldZeroed(
                buffer, PackedFileFormat.CHECKSUM_OFFSET, PackedFileFormat.CHECKSUM_LENGTH);
        buffer.putInt(PackedFileFormat.CHECKSUM_OFFSET, (int) crc);
        final Path path = tempDir.resolve(entityType + "-v1.dat");
        Files.write(path, v1);
        return path;
    }

    private static List<byte[]> ids(final String... items) {
        final List<byte[]> list = new ArrayList<>(items.length);
        for (final String item : items) {
            list.add(item.getBytes(StandardCharsets.UTF_8));
        }
        return list;
    }

    private static byte[] key(final int value) {
        return String.format("%08d", value).getBytes(StandardCharsets.US_ASCII);
    }

    private static List<byte[]> sequentialIds(final int count) {
        final List<byte[]> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(key(i));
        }
        return list;
    }

    @Test
    void findsPresentIdentifiersAndRejectsAbsentOnesAcrossManyBuckets() throws IOException {
        final PackedDeletionSet set =
                PackedDeletionSet.open(writeFile("user", sequentialIds(500), 8), "user");

        for (int i = 0; i < 500; i += 37) {
            assertTrue(set.contains(key(i)), "present " + i);
        }
        assertFalse(set.contains(key(500)));
        assertFalse(set.contains("00000037x".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(set.contains("!".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void emptyDatasetContainsNothing() throws IOException {
        final PackedDeletionSet set = PackedDeletionSet.open(writeFile("empty", ids(), 128), "empty");
        assertEquals(0, set.identifierCount());
        assertFalse(set.contains("anything".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void singleIdentifierDataset() throws IOException {
        final PackedDeletionSet set = PackedDeletionSet.open(writeFile("s", ids("solo"), 128), "s");
        assertTrue(set.contains("solo".getBytes(StandardCharsets.UTF_8)));
        assertFalse(set.contains("sol".getBytes(StandardCharsets.UTF_8)));
        assertFalse(set.contains("solon".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void findsIdentifiersContainingSupplementaryCharacters() throws IOException {
        // Sorted by UTF-8 bytes: "A" (41), U+FF21 (EF BC A1), U+1F600 (F0 9F 98 80),
        // U+1F680 (F0 9F 9A 80).
        final List<byte[]> identifiers = ids("A", "Ａ", "😀", "🚀");
        final PackedDeletionSet set = PackedDeletionSet.open(writeFile("emoji", identifiers, 2), "emoji");

        for (final byte[] identifier : identifiers) {
            assertTrue(set.contains(identifier));
        }
        assertFalse(set.contains("🎉".getBytes(StandardCharsets.UTF_8))); // U+1F389
    }

    @Test
    void matchesAnOracleForRandomMembership() throws IOException {
        final Random random = new Random(20260906L);
        final TreeSet<String> present = new TreeSet<>();
        while (present.size() < 3000) {
            present.add(Long.toHexString(random.nextLong() & 0xFFFF_FFFF_FFFFL));
        }
        final List<byte[]> identifiers = new ArrayList<>(present.size());
        for (final String value : present) {
            identifiers.add(value.getBytes(StandardCharsets.US_ASCII));
        }
        final PackedDeletionSet set = PackedDeletionSet.open(writeFile("hex", identifiers, 32), "hex");

        for (final String value : present) {
            assertTrue(set.contains(value.getBytes(StandardCharsets.US_ASCII)), value);
        }
        for (int i = 0; i < 3000; i++) {
            final String candidate = Long.toHexString(random.nextLong() & 0xFFFF_FFFF_FFFFL);
            assertEquals(
                    present.contains(candidate),
                    set.contains(candidate.getBytes(StandardCharsets.US_ASCII)),
                    candidate);
        }
    }

    @Test
    void containsIsSafeForConcurrentCallers() throws Exception {
        final PackedDeletionSet set =
                PackedDeletionSet.open(writeFile("user", sequentialIds(2000), 16), "user");

        final int threads = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    for (int i = 0; i < 5000; i++) {
                        if (!set.contains(key(i % 2000))
                                || set.contains(("z" + i).getBytes(StandardCharsets.US_ASCII))) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            for (final Future<Boolean> result : results) {
                assertTrue(result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void entityTypeAccessorReturnsTheHeaderValue() throws IOException {
        final PackedDeletionSet set = PackedDeletionSet.open(writeFile("order", ids("x"), 4), "order");
        assertEquals("order", set.entityType());
    }

    @Test
    void aFlippedByteFailsTheChecksum() throws IOException {
        final Path path = writeFile("user", sequentialIds(100), 8);
        final byte[] bytes = Files.readAllBytes(path);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(path, bytes);

        assertThrows(CorruptDatasetException.class, () -> PackedDeletionSet.open(path, "user"));
    }

    @Test
    void aTruncatedFileIsCorrupt() throws IOException {
        // A separate file per case: open() memory-maps the file, and on Windows that mapping keeps
        // the file locked against rewrites until it is garbage-collected, so a mapped path cannot be
        // truncated a second time in the same test.
        final byte[] full = PackedFileWriter.write("user", sequentialIds(100), 8);

        final Path head = tempDir.resolve("head.dat");
        Files.write(head, Arrays.copyOf(full, 40)); // shorter than the fixed header
        assertThrows(CorruptDatasetException.class, () -> PackedDeletionSet.open(head, "user"));

        final Path tail = tempDir.resolve("tail.dat");
        Files.write(tail, Arrays.copyOf(full, full.length - 16)); // header intact, body cut short
        assertThrows(CorruptDatasetException.class, () -> PackedDeletionSet.open(tail, "user"));
    }

    @Test
    void badMagicIsCorrupt() throws IOException {
        final Path path = writeFile("user", ids("a"), 4);
        final byte[] bytes = Files.readAllBytes(path);
        bytes[1] ^= 0xFF;
        Files.write(path, bytes);

        assertThrows(CorruptDatasetException.class, () -> PackedDeletionSet.open(path, "user"));
    }

    @Test
    void anUnknownFormatVersionIsAVersionMismatchNotCorruption() throws IOException {
        final Path path = writeFile("user", ids("a"), 4);
        final byte[] bytes = Files.readAllBytes(path);
        bytes[PackedFileFormat.FORMAT_VERSION_OFFSET] = 3; // past what this build reads

        Files.write(path, bytes);

        assertThrows(
                UnsupportedFormatVersionException.class,
                () -> PackedDeletionSet.open(path, "user"));
    }

    @Test
    void anEntityTypeMismatchIsCorrupt() throws IOException {
        final Path path = writeFile("user", ids("a"), 4);
        assertThrows(CorruptDatasetException.class, () -> PackedDeletionSet.open(path, "order"));
    }

    @Test
    void aMissingFileThrowsIoException() {
        assertThrows(
                IOException.class,
                () -> PackedDeletionSet.open(tempDir.resolve("absent.dat"), "user"));
    }

    @Test
    void theBloomFilterAcceleratesNegativesWithoutBreakingCorrectness() throws IOException {
        final PackedDeletionSet set =
                PackedDeletionSet.open(writeFile("user", sequentialIds(3000), 32), "user");
        for (int i = 0; i < 3000; i += 17) {
            assertTrue(set.contains(key(i)));
        }
        for (int i = 3000; i < 6000; i++) {
            assertFalse(set.contains(key(i)));
        }
    }

    @Test
    void aFileWrittenWithTheBloomFilterDisabledStillAnswersMembership() throws IOException {
        final Path path = tempDir.resolve("nobloom.dat");
        Files.write(path, PackedFileWriter.write("user", sequentialIds(500), 8, 1.0));
        final PackedDeletionSet set = PackedDeletionSet.open(path, "user");
        assertTrue(set.contains(key(42)));
        assertFalse(set.contains(key(500)));
    }

    @Test
    void readsALegacyV1File() throws IOException {
        final PackedDeletionSet set =
                PackedDeletionSet.open(writeV1File("user", sequentialIds(400)), "user");
        for (int i = 0; i < 400; i += 13) {
            assertTrue(set.contains(key(i)));
        }
        assertFalse(set.contains(key(400)));
        assertFalse(set.contains("00000010x".getBytes(StandardCharsets.US_ASCII)));
    }
}
