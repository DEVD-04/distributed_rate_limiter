package com.ratelimit.rules.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimit.rules.Algorithm;
import com.ratelimit.rules.FailureMode;
import com.ratelimit.rules.LimitBy;
import com.ratelimit.rules.Mode;
import com.ratelimit.rules.ParamsFactory;
import com.ratelimit.rules.Rule;
import com.ratelimit.rules.RuleSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON form of a {@link ConfigSnapshot}.
 *
 * <p>Encoding is deterministic (rules by id, keys by hash, fields in a fixed order), so the same
 * configuration always produces the same text and the publisher can tell by comparison whether the
 * stored copy is current.
 *
 * <p>Decoding is all or nothing. Every rule passes the same validation as anywhere else and the rules
 * must form a loadable {@link RuleSet}; if anything is wrong the whole snapshot is rejected, so a
 * gateway never applies half of a configuration.
 */
public final class SnapshotCodec {

    /** Bumped if the JSON shape ever changes incompatibly; readers refuse formats they do not know. */
    public static final int FORMAT = 1;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private record Document(int format, long version, List<RuleDocument> rules, List<ConfigSnapshot.ApiKeyEntry> apiKeys) {
    }

    private record RuleDocument(long ruleId, String name, LimitBy limitBy, String matchTier, String matchSubject,
            String endpointPattern, String httpMethod, Algorithm algorithm, Mode mode, FailureMode failureMode,
            Map<String, Number> params, int priority, long version) {

        static RuleDocument of(Rule r) {
            return new RuleDocument(r.ruleId(), r.name(), r.limitBy(), r.matchTier(), r.matchSubject(),
                    r.endpointPattern(), r.httpMethod(), r.algorithm(), r.mode(), r.failureMode(),
                    ParamsFactory.toMap(r.params()), r.priority(), r.version());
        }

        Rule toRule() {
            if (params == null || algorithm == null) {
                throw new IllegalArgumentException("rule " + ruleId + " needs an algorithm and params");
            }
            Map<String, String> asText = new LinkedHashMap<>();
            params.forEach((key, value) -> asText.put(key, String.valueOf(value)));
            return new Rule(ruleId, name, limitBy, matchTier, matchSubject, endpointPattern, httpMethod,
                    algorithm, mode, failureMode, ParamsFactory.parse(algorithm, asText), priority, version);
        }
    }

    private SnapshotCodec() {
    }

    public static String encode(ConfigSnapshot snapshot) {
        Document document = new Document(FORMAT, snapshot.version(),
                snapshot.rules().stream().sorted(Comparator.comparingLong(Rule::ruleId)).map(RuleDocument::of).toList(),
                snapshot.apiKeys().stream().sorted(Comparator.comparing(ConfigSnapshot.ApiKeyEntry::keyHash)).toList());
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode the snapshot", e);
        }
    }

    /** @throws IllegalArgumentException if the text is not a complete, valid snapshot of a known format */
    public static ConfigSnapshot decode(String text) {
        try {
            JsonNode tree = JSON.readTree(text);
            if (tree == null || !tree.path("format").isInt() || tree.path("format").intValue() != FORMAT) {
                throw new IllegalArgumentException("unsupported snapshot format (expected " + FORMAT + ")");
            }
            Document document = JSON.readValue(text, Document.class);
            if (document.rules() == null || document.apiKeys() == null) {
                throw new IllegalArgumentException("snapshot is missing its rules or API keys");
            }
            List<Rule> rules = document.rules().stream().map(RuleDocument::toRule).toList();
            RuleSet.of(document.version(), rules); // the same whole-set checks a gateway applies
            return new ConfigSnapshot(document.version(), rules, document.apiKeys());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("snapshot is not valid: " + e.getOriginalMessage(), e);
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("snapshot is missing a required value: " + e.getMessage(), e);
        }
    }
}
