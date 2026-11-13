package com.ratelimit.gateway.proxy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catch-all handler: anything the gateway does not serve itself is proxied. More specific
 * mappings (for example the metrics endpoint) take precedence over this one.
 */
@RestController
class ProxyController {

    private final ProxyService proxy;

    ProxyController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping("/**")
    void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        proxy.forward(request, response);
    }
}
