plugins {
    application
    id("deletionchecker.java-conventions")
}

dependencies {
    implementation(project(":lib"))
    implementation(libs.picocli)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.marksayson.deletionchecker.generator.GeneratorCli"
}
