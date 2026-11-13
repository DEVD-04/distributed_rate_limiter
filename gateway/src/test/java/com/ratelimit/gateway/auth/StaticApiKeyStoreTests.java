package com.ratelimit.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StaticApiKeyStoreTests {

    private static AuthProperties.ApiKey key(String raw, String user, String tier, boolean active) {
        return new AuthProperties.ApiKey(ApiKeyStore.sha256Hex(raw), user, tier, active);
    }

    @Test
    void sha256HexMatchesKnownVector() {
        assertThat(ApiKeyStore.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void findsActiveKeyByHash() {
        var store = new StaticApiKeyStore(List.of(key("k-1", "bob", "free", true)));

        String hash = ApiKeyStore.sha256Hex("k-1");
        assertThat(store.findByHash(hash)).contains(Identity.apiKey("bob", "free", hash));
        assertThat(store.findByHash(ApiKeyStore.sha256Hex("k-2"))).isEmpty();
    }

    @Test
    void inactiveKeysAreNotFound() {
        var store = new StaticApiKeyStore(List.of(key("k-1", "bob", "free", false)));
        assertThat(store.findByHash(ApiKeyStore.sha256Hex("k-1"))).isEmpty();
    }

    @Test
    void configuredHashIsCaseInsensitive() {
        String upper = ApiKeyStore.sha256Hex("k-1").toUpperCase();
        var store = new StaticApiKeyStore(List.of(new AuthProperties.ApiKey(upper, "bob", "free", true)));
        assertThat(store.findByHash(ApiKeyStore.sha256Hex("k-1"))).isPresent();
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new StaticApiKeyStore(List.of(new AuthProperties.ApiKey("plain-key", "bob", "free", true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaticApiKeyStore(List.of(key("k", "", "free", true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StaticApiKeyStore(List.of(key("k", "bob", null, true))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
