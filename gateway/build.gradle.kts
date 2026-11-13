// Gateway dependencies are added phase by phase (auth, rate limiting, proxy, metrics).
dependencies {
    // Strict IP-literal parsing (never does DNS lookups) for X-Forwarded-For handling.
    implementation("com.google.guava:guava:33.8.0-jre")

    // JWT parsing and RS256 signature verification.
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")

    // Bounded, expiring in-memory state (version managed by the Spring Boot BOM).
    implementation("com.github.ben-manes.caffeine:caffeine")
}
