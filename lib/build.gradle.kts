plugins {
    `java-library`
    checkstyle
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Avoid runtime dependencies, removing risk of version conflicts for library consumers.
// Everything the library needs is in the JDK — keep it that way while feasible.
val checkNoRuntimeDependencies by tasks.registering {
    description = "Fails if :lib resolves any runtime dependency."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val runtimeFiles = configurations.runtimeClasspath.get().incoming.files
    doLast {
        val names = runtimeFiles.files.map { it.name }.sorted()
        require(names.isEmpty()) {
            ":lib must have zero runtime dependencies but runtimeClasspath resolved: $names"
        }
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    maxWarnings = 0
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to "com.marksayson.deletionchecker")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
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
    dependsOn(tasks.named("jacocoTestCoverageVerification"), checkNoRuntimeDependencies)
}
