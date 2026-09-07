plugins {
    `java-library`
    id("deletionchecker.java-conventions")
}

dependencies {
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.named<Jar>("jar") {
    manifest {
        attributes("Automatic-Module-Name" to "com.marksayson.deletionchecker")
    }
}

tasks.named("check") {
    dependsOn(checkNoRuntimeDependencies)
}
