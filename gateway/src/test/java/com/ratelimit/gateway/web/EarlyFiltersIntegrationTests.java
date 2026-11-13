package com.ratelimit.gateway.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Runs the real filters registered by Spring Boot, configured with a trusted nginx range. The
 * paths used here are public so authentication does not get in the way of what is tested.
 */
@SpringBootTest(properties = {
        "gateway.trusted-proxies=172.18.0.0/16",
        "gateway.auth.public-paths=/test/whoami,/does-not-exist"})
@AutoConfigureMockMvc
class EarlyFiltersIntegrationTests {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor peer(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void behindTrustedProxyTheClientIpComesFromForwardedFor() throws Exception {
        mvc.perform(get("/test/whoami")
                        .with(peer("172.18.0.2"))
                        .header("X-Forwarded-For", "6.6.6.6, 203.0.113.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientIp").value("203.0.113.9"));
    }

    @Test
    void directUntrustedCallerCannotSpoofItsIp() throws Exception {
        mvc.perform(get("/test/whoami")
                        .with(peer("198.51.100.7"))
                        .header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientIp").value("198.51.100.7"));
    }

    @Test
    void suppliedRequestIdIsEchoedInTheResponseAndSeenByTheHandler() throws Exception {
        mvc.perform(get("/test/whoami").header("X-Request-Id", "abc-123"))
                .andExpect(header().string("X-Request-Id", "abc-123"))
                .andExpect(jsonPath("$.requestId").value("abc-123"));
    }

    @Test
    void requestIdIsAddedEvenToUnmatchedPaths() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Request-Id"));
    }
}
