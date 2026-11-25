package com.ratelimit.gateway.rules;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds typed {@link Params} from the loose key/value form used in configuration files and in the
 * {@code params} JSON column. Keys may be written {@code refill_per_sec} or {@code refill-per-sec}.
 * Unknown keys are rejected so a typo such as {@code capcity} fails loudly instead of silently
 * falling back to a default.
 */
public final class ParamsFactory {

    private ParamsFactory() {
    }

    public static Params parse(Algorithm algorithm, Map<String, String> raw) {
        Map<String, String> params = normalize(raw);
        return switch (algorithm) {
            case TOKEN_BUCKET -> tokenBucket(params);
            case SLIDING_WINDOW_LOG -> slidingWindow(params);
            case LEAKY_BUCKET_QUEUE ->
                    throw new IllegalArgumentException("algorithm " + algorithm + " is not implemented yet");
        };
    }

    private static Params slidingWindow(Map<String, String> params) {
        rejectUnknown(params, Set.of("limit", "window_ms"));
        return new SlidingWindowParams(requireLong(params, "limit"), requireLong(params, "window_ms"));
    }

    private static Params tokenBucket(Map<String, String> params) {
        rejectUnknown(params, Set.of("capacity", "refill_per_sec", "cost"));
        long capacity = requireLong(params, "capacity");
        double refill = requireDouble(params, "refill_per_sec");
        int cost = params.containsKey("cost") ? (int) parseLong("cost", params.get("cost")) : 1;
        return new TokenBucketParams(capacity, refill, cost);
    }

    private static Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> result = new HashMap<>();
        raw.forEach((key, value) -> {
            String normalized = key.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (result.put(normalized, value == null ? "" : value.trim()) != null) {
                throw new IllegalArgumentException("parameter '" + normalized + "' is given twice");
            }
        });
        return result;
    }

    private static void rejectUnknown(Map<String, String> params, Set<String> allowed) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown parameter '" + key + "', allowed: " + allowed);
            }
        }
    }

    private static long requireLong(Map<String, String> params, String key) {
        return parseLong(key, require(params, key));
    }

    private static double requireDouble(Map<String, String> params, String key) {
        String value = require(params, key);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a number, was '" + value + "'");
        }
    }

    private static long parseLong(String key, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + key + "' must be a whole number, was '" + value + "'");
        }
    }

    private static String require(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("missing parameter '" + key + "'");
        }
        return value;
    }
}
