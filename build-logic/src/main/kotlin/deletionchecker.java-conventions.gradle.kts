import org.gradle.api.artifacts.VersionCatalogsExtension

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Turn a hung test (e.g. a broken binary search) into a fast failure instead of a stuck CI job.
    // separate_thread is required: the default mode only checks elapsed time after the method returns.
    systemProperty("junit.jupiter.execution.timeout.testable.method.default", "10s")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "separate_thread")
    finalizedBy(tasks.named("jacocoTestReport"))
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

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
