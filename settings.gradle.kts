pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}


plugins {
    // Auto-provisions JDK toolchains (e.g. JDK 21 required by WebStorm 2024.2)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "fnm-webstorm"

