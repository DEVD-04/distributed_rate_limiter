package com.ratelimit.mock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "mock.instance-id=test-1")
@AutoConfigureMockMvc
class MockRoutingTests {

    @Autowired
    MockMvc mvc;

    @Test
    void imagesRouteIsServedByImageService() throws Exception {
        mvc.perform(get("/api/images/thumbnail/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("image-service"))
                .andExpect(jsonPath("$.instance").value("test-1"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.path").value("/api/images/thumbnail/42"))
                .andExpect(jsonPath("$.delayMs").value(0));
    }

    @Test
    void textRouteIsServedByTextService() throws Exception {
        mvc.perform(get("/api/text/summarize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("text-service"));
    }

    @Test
    void anyHttpMethodIsAccepted() throws Exception {
        mvc.perform(post("/api/text/generate").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("POST"));
    }

    @Test
    void forwardedHeadersAreEchoedBack() throws Exception {
        mvc.perform(get("/api/images/x")
                        .header("X-Request-Id", "req-123")
                        .header("X-User-Id", "alice")
                        .header("X-Tier", "pro")
                        .header("X-Forwarded-For", "10.0.0.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedHeaders['X-Request-Id']").value("req-123"))
                .andExpect(jsonPath("$.receivedHeaders['X-User-Id']").value("alice"))
                .andExpect(jsonPath("$.receivedHeaders['X-Tier']").value("pro"))
                .andExpect(jsonPath("$.receivedHeaders['X-Forwarded-For']").value("10.0.0.7"));
    }

    @Test
    void absentHeadersAreNotEchoed() throws Exception {
        mvc.perform(get("/api/images/x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedHeaders").isEmpty());
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        mvc.perform(get("/api/video/1")).andExpect(status().isNotFound());
    }
}
