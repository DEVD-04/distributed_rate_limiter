package com.ratelimit.gateway.proxy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** Maps a request path to the pool of the service that should handle it; the most specific pattern wins. */
public final class RouteTable {

    private record Route(PathPattern pattern, UpstreamPool pool) {
    }

    private final List<Route> routes;

    private RouteTable(List<Route> routes) {
        this.routes = routes;
    }

    /** @throws IllegalArgumentException if a route is invalid; the message names the route's position */
    public static RouteTable of(ProxyProperties properties, LongSupplier nanoClock) {
        PathPatternParser parser = new PathPatternParser();
        List<Route> routes = new ArrayList<>();
        Set<String> services = new HashSet<>();
        Set<String> patterns = new HashSet<>();

        for (int i = 0; i < properties.routes().size(); i++) {
            ProxyProperties.RouteEntry entry = properties.routes().get(i);
            String where = "routes[" + i + "]";
            if (entry.pattern() == null || !entry.pattern().startsWith("/")) {
                throw new IllegalArgumentException(where + ": pattern must start with '/'");
            }
            if (entry.service() == null || entry.service().isBlank()) {
                throw new IllegalArgumentException(where + ": service name is required");
            }
            if (!services.add(entry.service())) {
                throw new IllegalArgumentException(where + ": duplicate service '" + entry.service() + "'");
            }
            if (!patterns.add(entry.pattern())) {
                throw new IllegalArgumentException(where + ": duplicate pattern '" + entry.pattern() + "'");
            }
            if (entry.instances() == null || entry.instances().isEmpty()) {
                throw new IllegalArgumentException(where + ": at least one instance is required");
            }
            entry.instances().forEach(uri -> validateInstance(where, uri));

            PathPattern pattern;
            try {
                pattern = parser.parse(entry.pattern());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": invalid pattern '" + entry.pattern() + "': " + e.getMessage(), e);
            }
            UpstreamPool pool = new UpstreamPool(entry.service(), entry.instances(),
                    properties.ejection().failureThreshold(), properties.ejection().cooldown().toNanos(), nanoClock);
            routes.add(new Route(pattern, pool));
        }
        routes.sort(Comparator.comparing(Route::pattern, PathPattern.SPECIFICITY_COMPARATOR));
        return new RouteTable(List.copyOf(routes));
    }

    private static void validateInstance(String where, URI uri) {
        boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalArgumentException(where + ": instance must be an http(s) URL with a host, was '" + uri + "'");
        }
        boolean hasPath = uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/");
        if (hasPath || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(where + ": instance must be just scheme://host[:port], was '" + uri + "'");
        }
    }

    public Optional<UpstreamPool> find(String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (Route route : routes) {
            if (route.pattern().matches(container)) {
                return Optional.of(route.pool());
            }
        }
        return Optional.empty();
    }

    /** The pool of a named service; mainly for tests and health reporting. */
    public Optional<UpstreamPool> pool(String service) {
        return routes.stream().map(Route::pool).filter(p -> p.service().equals(service)).findFirst();
    }
}
