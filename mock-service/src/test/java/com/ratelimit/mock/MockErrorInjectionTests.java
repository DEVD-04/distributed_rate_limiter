package com.ratelimit.mock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"mock.error-rate=1.0", "mock.error-status=503"})
@AutoConfigureMockMvc
class MockErrorInjectionTests {

    @Autowired
    MockMvc mvc;

    @Test
    void everyRequestFailsWithConfiguredStatusWhenErrorRateIsOne() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/text/anything"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error").value("injected_failure"))
                    .andExpect(jsonPath("$.service").value("text-service"));
        }
    }
}
