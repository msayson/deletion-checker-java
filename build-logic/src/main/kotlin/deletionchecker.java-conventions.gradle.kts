import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

// Shared Java build conventions for every module: toolchain, Checkstyle, JaCoCo (with the hard
// 90% line + branch coverage gate), and the JUnit test setup. Module build files add only what is
// specific to them (dependencies, jar manifest, the lib-only zero-runtime-dependency check).

plugins {
    java
    checkstyle
    jacoco
}

repositories {
    mavenCentral()
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// `deletioncheckerConventions { enforceCoverageGate = false }` opts a module out of the 90% gate —
// used by :benchmarks, whose sources are all @Tag("bench") harness code excluded from `test`.
interface DeletioncheckerConventionsExtension {
    val enforceCoverageGate: org.gradle.api.provider.Property<Boolean>
}
val conventions = extensions.create<DeletioncheckerConventionsExtension>("deletioncheckerConventions")
conventions.enforceCoverageGate.convention(true)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    maxWarnings = 0
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("perf", "bench")
    }
    // Turn a hung test (e.g. a broken binary search) into a fast failure instead of a stuck CI job.
    // separate_thread is required: the default mode only checks elapsed time after the method returns.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "10s")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "separate_thread")
    finalizedBy(tasks.named("jacocoTestReport"))
}

// The @Tag("perf") benchmarks. Manual — run with `./gradlew perfTest`; never part of `check`, and
// their exec data never reaches the coverage report or its gate. No 10s method timeout here.
tasks.register<Test>("perfTest") {
    description = "Runs the @Tag(\"perf\") performance benchmarks."
    group = "verification"
    useJUnitPlatform {
        includeTags("perf")
        excludeTags("bench")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "2g"
    System.getProperty("perf.size")?.let { systemProperty("perf.size", it) }
    // No JaCoCo agent — instrumentation would inflate the latency it is asserting against.
    extensions.getByType(JacocoTaskExtension::class.java).isEnabled = false
    testLogging {
        showStandardStreams = true
        showExceptions = true
    }
    outputs.upToDateWhen { false }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

afterEvaluate {
    if (conventions.enforceCoverageGate.get()) {
        tasks.named("check") {
            dependsOn(tasks.named("jacocoTestCoverageVerification"))
        }
    }
}
