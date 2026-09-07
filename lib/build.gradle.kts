plugins {
    `java-library`
    checkstyle
}

repositories {
    mavenCentral()
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    maxWarnings = 0
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

tasks.named<Test>("test") {
    useJUnitPlatform()
}
