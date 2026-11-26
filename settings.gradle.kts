plugins {
    // Lets Gradle download the JDK requested by the toolchain (Java 21) when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rate-limit"

include("rules-core", "gateway", "mock-service", "policy-service")
