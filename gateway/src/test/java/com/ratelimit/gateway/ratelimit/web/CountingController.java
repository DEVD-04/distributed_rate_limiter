package com.ratelimit.gateway.ratelimit.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that records how many times each URI actually reached the handler. */
@RestController
class CountingController {

    static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    static int hits(String uri) {
        AtomicInteger counter = HITS.get(uri);
        return counter == null ? 0 : counter.get();
    }

    @RequestMapping({"/test/ip/**", "/test/user/**", "/test/keyed/**", "/test/open/**"})
    Map<String, String> handle(HttpServletRequest request) {
        HITS.computeIfAbsent(request.getRequestURI(), k -> new AtomicInteger()).incrementAndGet();
        return Map.of("status", "ok");
    }
}
