import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// Modules that are plain libraries; every other module is a runnable Spring Boot web application.
val libraryModules = setOf("rules-core")

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "com.ratelimit"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    // Library and application modules take their dependency versions from the same Spring Boot BOM.
    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    if (name !in libraryModules) {
        apply(plugin = "org.springframework.boot")
        dependencies {
            "implementation"("org.springframework.boot:spring-boot-starter-web")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
