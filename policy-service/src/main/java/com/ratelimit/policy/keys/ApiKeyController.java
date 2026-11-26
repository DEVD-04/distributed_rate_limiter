package com.ratelimit.policy.keys;

import com.ratelimit.policy.keys.ApiKeyService.ApiKeyView;
import com.ratelimit.policy.keys.ApiKeyService.CreatedApiKey;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api-keys")
class ApiKeyController {

    record CreateApiKeyRequest(String userId, String tier) {
    }

    private final ApiKeyService service;

    ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    List<ApiKeyView> list() {
        return service.list();
    }

    @PostMapping
    ResponseEntity<CreatedApiKey> create(@RequestBody CreateApiKeyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()) // the response holds the secret
                .body(service.create(request.userId(), request.tier()));
    }

    @DeleteMapping("/{keyHash}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable String keyHash) {
        service.revoke(keyHash);
    }
}
