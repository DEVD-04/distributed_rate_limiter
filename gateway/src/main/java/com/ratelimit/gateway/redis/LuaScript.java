package com.ratelimit.gateway.redis;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A Lua script run with {@code EVALSHA}, so only a 40-character hash crosses the wire per call.
 * If Redis does not know the script (first use, restart, or {@code SCRIPT FLUSH}) it is loaded
 * and the call is repeated once.
 */
public final class LuaScript {

    private final String name;
    private final String source;
    private final String sha;

    public LuaScript(String name, String source) {
        this.name = name;
        this.source = source;
        this.sha = sha1(source);
    }

    public String name() {
        return name;
    }

    /** @return the script's integer-array result */
    @SuppressWarnings("unchecked")
    public List<Long> run(StatefulRedisConnection<String, String> connection, List<String> keys, String... args) {
        String[] keyArray = keys.toArray(String[]::new);
        try {
            return (List<Long>) connection.sync().evalsha(sha, ScriptOutputType.MULTI, keyArray, args);
        } catch (RedisNoScriptException e) {
            connection.sync().scriptLoad(source);
            return (List<Long>) connection.sync().evalsha(sha, ScriptOutputType.MULTI, keyArray, args);
        }
    }

    private static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }
}
