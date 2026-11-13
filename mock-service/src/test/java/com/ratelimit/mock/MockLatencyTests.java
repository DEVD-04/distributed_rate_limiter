package com.ratelimit.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"mock.latency-ms=150", "mock.latency-jitter-ms=0"})
@AutoConfigureMockMvc
class MockLatencyTests {

    @Autowired
    MockMvc mvc;

    @Test
    void configuredLatencyIsApplied() throws Exception {
        long start = System.nanoTime();
        mvc.perform(get("/api/images/slow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delayMs").value(150));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }
}
