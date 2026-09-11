plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Applied by id("com.github.spotbugs") in the conventions script below. Unlike Checkstyle/JaCoCo
    // (Gradle core plugins) SpotBugs needs its plugin on build-logic's own classpath to be applicable.
    // Version kept here, not in the root catalog: only build-logic needs it. The analysis engine
    // version (`spotbugs.toolVersion`, used per-module) is in gradle/libs.versions.toml instead.
    implementation("com.github.spotbugs.snom:spotbugs-gradle-plugin:6.5.11")
}
