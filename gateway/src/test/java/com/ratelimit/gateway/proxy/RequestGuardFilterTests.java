package com.ratelimit.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestGuardFilterTests {

    @Test
    void ordinaryPathsAreAccepted() {
        for (String path : List.of("/", "/api/images/cat.png", "/api/a%20b", "/a/b/", "/a.b/c..d/e",
                "/api/v1.2/x", "/api/%7Euser", "/a/...", "/a/.hidden", "/a/b%2")) {
            assertThat(RequestGuardFilter.isCanonical(path)).as(path).isTrue();
        }
    }

    @Test
    void dotSegmentsAreRejected() {
        for (String path : List.of("/..", "/.", "/a/..", "/a/../b", "/a/./b", "/../etc/passwd", "/a/b/..")) {
            assertThat(RequestGuardFilter.isCanonical(path)).as(path).isFalse();
        }
    }

    @Test
    void encodedDotsSlashesAndBackslashesAreRejectedInAnyCase() {
        for (String path : List.of("/a/%2e%2e/b", "/a/%2E%2E/b", "/a/%2e/b", "/a%2fb", "/a%2Fb", "/a%5cb", "/a%5Cb", "/a/.%2e/b")) {
            assertThat(RequestGuardFilter.isCanonical(path)).as(path).isFalse();
        }
    }

    @Test
    void emptySegmentsAreRejected() {
        for (String path : List.of("//", "/a//b", "//a", "/a//")) {
            assertThat(RequestGuardFilter.isCanonical(path)).as(path).isFalse();
        }
    }

    @Test
    void pathParametersBackslashesAndControlOrNonAsciiCharactersAreRejected() {
        for (String path : List.of("/a;jsessionid=1", "/a\\b", "/a b", "/a\tb", "/a\nb", "/a\u0000b", "/ä", "/a\u007fb", "/a%00b")) {
            assertThat(RequestGuardFilter.isCanonical(path)).as(path.replace("\u0000", "<NUL>")).isFalse();
        }
    }

    @Test
    void pathsMustBeAbsolute() {
        assertThat(RequestGuardFilter.isCanonical(null)).isFalse();
        assertThat(RequestGuardFilter.isCanonical("")).isFalse();
        assertThat(RequestGuardFilter.isCanonical("api/images")).isFalse();
        assertThat(RequestGuardFilter.isCanonical("http://evil/")).isFalse();
    }
}
