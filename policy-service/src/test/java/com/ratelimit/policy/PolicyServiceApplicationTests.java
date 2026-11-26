package com.ratelimit.policy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "policy.admin-token=test-admin-token-0123456789")
@Import(PostgresTestSupport.class)
class PolicyServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
