package com.marksayson.deletionchecker.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratorConfigTest {

    @Test
    void acceptsAValidConfiguration() {
        final GeneratorConfig config = new GeneratorConfig("3.2.1", "2026-09-06T17:00:00Z", 64);
        assertEquals("3.2.1", config.generatorVersion());
        assertEquals(64, config.bucketSize());
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new GeneratorConfig(null, "2026-09-06T17:00:00Z", 128));
        assertThrows(NullPointerException.class, () -> new GeneratorConfig("1.0.0", null, 128));
    }

    @Test
    void rejectsAGeneratorVersionNotBeginningWithADigit() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratorConfig("v3", "2026-09-06T17:00:00Z", 128)); // above '9'
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratorConfig("-1", "2026-09-06T17:00:00Z", 128)); // below '0'
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratorConfig("", "2026-09-06T17:00:00Z", 128)); // empty
    }

    @Test
    void rejectsANonTimestampDatasetVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratorConfig("1.0.0", "2026-09-06", 128));
    }

    @Test
    void rejectsABucketSizeBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneratorConfig("1.0.0", "2026-09-06T17:00:00Z", 0));
    }
}
