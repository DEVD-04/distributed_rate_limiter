package com.ratelimit.policy.publish;

import com.ratelimit.policy.config.ConfigVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shows an operator whether the latest change has reached Redis. */
@RestController
class VersionController {

    private final ConfigVersion version;
    private final SnapshotPublisher publisher;

    VersionController(ConfigVersion version, SnapshotPublisher publisher) {
        this.version = version;
        this.publisher = publisher;
    }

    /** {@code publishedVersion} is null if publishing is off, Redis is unreachable, or nothing is there. */
    @GetMapping("/admin/version")
    Map<String, Long> version() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("version", version.current());
        result.put("publishedVersion", publisher.publishedVersion().orElse(null));
        return result;
    }
}
