package com.ratelimit.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicKeyLoaderTests {

    private static final KeyPair KEYS = TokenFactory.newRsaKeyPair();

    @Test
    void loadsInlinePem() {
        var key = PublicKeyLoader.load(TokenFactory.publicPem(KEYS));
        assertThat(key.getEncoded()).isEqualTo(KEYS.getPublic().getEncoded());
    }

    @Test
    void loadsPemFromAFileLocation(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("public.pem");
        Files.writeString(file, TokenFactory.publicPem(KEYS));

        var key = PublicKeyLoader.load(file.toUri().toString());

        assertThat(key.getEncoded()).isEqualTo(KEYS.getPublic().getEncoded());
    }

    @Test
    void rejectsMissingFile(@TempDir Path dir) {
        assertThatThrownBy(() -> PublicKeyLoader.load(dir.resolve("absent.pem").toUri().toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsKeysSmallerThan2048Bits() {
        String pem = TokenFactory.publicPem(TokenFactory.newRsaKeyPair(1024));
        assertThatThrownBy(() -> PublicKeyLoader.load(pem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void rejectsNonRsaKeys() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        assertThatThrownBy(() -> PublicKeyLoader.load(pem)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsGarbageInsideAPemBlock() {
        assertThatThrownBy(() -> PublicKeyLoader.load("-----BEGIN PUBLIC KEY-----\n!!!!\n-----END PUBLIC KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPrivateKeyBlocks() {
        assertThatThrownBy(() -> PublicKeyLoader.parse("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
