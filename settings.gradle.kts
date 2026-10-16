plugins {
    // Lets Gradle download the JDK required by the toolchain (build.gradle.kts) if none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "bookstore"
