import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    java
    id("deletionchecker.java-conventions")
}

// Harness code is all @Tag("bench"), excluded from `test`, so there is nothing for the coverage
// gate to score. Checkstyle still applies.
deletioncheckerConventions {
    enforceCoverageGate = false
}

dependencies {
    testImplementation(project(":lib"))
    testImplementation(project(":dataset-generator")) // build datasets via the real generator path
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Local-only benchmarks (`ComparativeBenchmarkTest` vs HashSet, `BucketSizeBenchmarkTest` over K).
// Not wired into `check` or CI. Full default run is ~10-25 min — see docs/benchmarks/README.md.
// Run one class with `--tests '*BucketSize*'`.
tasks.register<Test>("benchmark") {
    description = "Runs the @Tag(\"bench\") benchmark suites."
    group = "verification"
    useJUnitPlatform {
        includeTags("bench")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = System.getProperty("bench.xmx") ?: "7g" // the 10M cells hold ~4-5 GB live
    listOf(
        "bench.shapes", "bench.sizes", "bench.typeCounts", "bench.typeSweepSize", "bench.measured",
        "bench.publish", "bench.bloomFpr",
        "bench.k.values", "bench.k.shapes", "bench.k.typeCounts", "bench.k.size", "bench.k.measured",
    ).forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
    // Deterministic explicit-GC behaviour for the heap-footprint measurement.
    jvmArgs("-XX:+UseParallelGC")
    // No JaCoCo agent — instrumentation would inflate the lib code paths being measured.
    extensions.getByType(JacocoTaskExtension::class.java).isEnabled = false
    testLogging {
        showStandardStreams = true
        showExceptions = true
    }
    outputs.upToDateWhen { false }
}
