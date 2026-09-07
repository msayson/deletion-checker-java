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

// Local-only comparative benchmark (packed set vs HashSet<String>). Not wired into `check` or CI.
// Full default sweep is ~15-25 min and needs headroom — see docs/benchmarks/README.md.
tasks.register<Test>("benchmark") {
    description = "Runs the @Tag(\"bench\") comparative benchmark suite."
    group = "verification"
    useJUnitPlatform {
        includeTags("bench")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = System.getProperty("bench.xmx") ?: "7g" // the 10M cells hold ~4-5 GB live
    for (key in listOf(
        "bench.shapes", "bench.sizes", "bench.typeCounts", "bench.typeSweepSize",
        "bench.measured", "bench.publish",
    )) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // Deterministic explicit-GC behaviour for the heap-footprint measurement.
    jvmArgs("-XX:+UseParallelGC")
    testLogging {
        showStandardStreams = true
        showExceptions = true
    }
    outputs.upToDateWhen { false }
}
