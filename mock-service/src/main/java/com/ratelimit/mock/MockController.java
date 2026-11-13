package com.ratelimit.mock;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for the downstream services. {@code /api/images/**} plays the image service and
 * {@code /api/text/**} the text service; both accept any HTTP method.
 */
@RestController
public class MockController {

    /** Headers the gateway forwards; echoed back so tests can verify them. */
    private static final List<String> ECHOED_HEADERS =
            List.of("X-Request-Id", "X-User-Id", "X-Tier", "X-Forwarded-For");

    private final MockProperties properties;
    private final FaultInjector faults;

    public MockController(MockProperties properties, FaultInjector faults) {
        this.properties = properties;
        this.faults = faults;
    }

    @RequestMapping({"/api/images/**", "/api/text/**"})
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest request) {
        long delay = faults.delayMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "interrupted", "instance", properties.instanceId()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", serviceFor(request.getRequestURI()));
        body.put("instance", properties.instanceId());
        body.put("method", request.getMethod());
        body.put("path", request.getRequestURI());
        body.put("timestamp", Instant.now().toString());
        body.put("delayMs", delay);

        Map<String, String> received = new LinkedHashMap<>();
        for (String name : ECHOED_HEADERS) {
            String value = request.getHeader(name);
            if (value != null) {
                received.put(name, value);
            }
        }
        body.put("receivedHeaders", received);

        if (faults.shouldFail()) {
            body.put("error", "injected_failure");
            return ResponseEntity.status(properties.errorStatus()).body(body);
        }
        return ResponseEntity.ok(body);
    }

    private static String serviceFor(String uri) {
        return uri.startsWith("/api/images") ? "image-service" : "text-service";
    }
}
