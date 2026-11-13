package com.ratelimit.gateway.web;

import com.ratelimit.gateway.auth.Identity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints exposing what the early filters computed. */
@RestController
class WhoAmIController {

    @GetMapping({"/test/whoami", "/test/public"})
    Map<String, String> whoAmI(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("requestId", RequestContext.requestId(request));
        result.put("clientIp", RequestContext.clientIp(request));
        Identity identity = Identity.of(request);
        if (identity != null) {
            result.put("userId", identity.userId());
            result.put("tier", identity.tier());
            result.put("method", identity.method().name());
        }
        return result;
    }
}
