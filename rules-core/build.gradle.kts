// The rate-limit rule model, validation and matching, shared by the gateway (which applies rules) and
// the policy service (which validates them before publishing), so both always agree on what is valid.
dependencies {
    // Strict IP-literal parsing, to require canonical IP subjects.
    implementation("com.google.guava:guava:33.8.0-jre")

    // Spring's PathPattern: how endpoint patterns are parsed, matched and ranked by specificity.
    implementation("org.springframework:spring-web")

    // Bounded cache of resolved rule candidates.
    implementation("com.github.ben-manes.caffeine:caffeine")
}
