package com.ratelimit.gateway.proxy;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxyConfig {

    /**
     * HTTP/1.1 only (the downstream services speak plain HTTP/1.1, and an h2c upgrade attempt would
     * be pointless), no redirect following (a redirect is the upstream's answer to pass on), and
     * virtual threads for the client's internal work.
     */
    @Bean
    HttpClient upstreamHttpClient(ProxyProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Bean
    RouteTable routeTable(ProxyProperties properties) {
        try {
            return RouteTable.of(properties, System::nanoTime);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gateway.proxy." + e.getMessage(), e);
        }
    }
}
