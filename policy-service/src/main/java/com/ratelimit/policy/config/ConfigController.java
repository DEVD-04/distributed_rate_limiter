package com.ratelimit.policy.config;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ConfigController {

    private final ConfigVersion version;

    ConfigController(ConfigVersion version) {
        this.version = version;
    }

    @GetMapping("/admin/version")
    Map<String, Long> version() {
        return Map.of("version", version.current());
    }
}
