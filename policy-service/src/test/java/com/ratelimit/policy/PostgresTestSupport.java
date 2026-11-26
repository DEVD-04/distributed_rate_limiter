package com.ratelimit.policy;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL for tests, wired in as the application's datasource. Spring keeps one application
 * context (and so one container) for all test classes that import this.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestSupport {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }
}
