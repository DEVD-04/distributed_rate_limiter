// Policy service: the admin side of the rules (database, validation, publishing). Not in the request path.
dependencies {
    // The same rule model and validation the gateway applies.
    implementation(project(":rules-core"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Schema migrations (versions managed by the Spring Boot BOM).
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Real PostgreSQL in tests, started in Docker.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
