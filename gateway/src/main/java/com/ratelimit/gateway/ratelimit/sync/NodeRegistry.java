package com.ratelimit.gateway.ratelimit.sync;

import com.ratelimit.gateway.redis.LuaScript;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Knows how many gateway nodes are running, which sets each node's fair share of a shared token bucket.
 *
 * <p>Every node reports itself to a sorted set in Redis on a timer (score = time of the report, from
 * Redis's clock, so the nodes' own clocks never matter). Each report also drops the nodes not heard from
 * within the node timeout and returns how many are left. A node that shuts down normally removes itself,
 * so the others learn of it at their next report; one that dies is dropped after the timeout.
 *
 * <p>While reports fail, the last count is kept: it is the best estimate there is. Before any report has
 * succeeded the count is 1. A wrong count only makes the shares unfair for a moment; it cannot make the
 * cluster admit more than the limit, because every share still comes out of the one shared bucket.
 */
public class NodeRegistry implements AutoCloseable {

    /** Sorted set of node ids, each scored with the time of its latest report. */
    public static final String KEY = "rl:nodes";

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    /** KEYS[1] the set; ARGV: node id, node timeout in ms. Returns {nodes heard from within the timeout}. */
    static final LuaScript HEARTBEAT = new LuaScript("node_heartbeat", """
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            local timeout = tonumber(ARGV[2])
            redis.call('ZADD', KEYS[1], now, ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - timeout)
            redis.call('PEXPIRE', KEYS[1], timeout) -- nobody left to report: the set disappears
            return {redis.call('ZCARD', KEYS[1])}
            """);

    private final Supplier<StatefulRedisConnection<String, String>> connection;
    private final String nodeId;
    private final Duration interval;
    private final Duration timeout;

    private volatile int nodeCount = 1;
    // used only by the thread that is reporting
    private long lastSuccessNanos = System.nanoTime();
    private boolean warned;

    private ScheduledExecutorService worker;
    private boolean closed;

    /**
     * @param connection asked for on every report; it may throw while Redis has not been reached
     * @param nodeId     unique among the nodes sharing this Redis
     * @param interval   how often this node reports
     * @param timeout    how long after its latest report a node still counts; at least two intervals, so that
     *                   one late report does not make a running node disappear
     */
    public NodeRegistry(Supplier<StatefulRedisConnection<String, String>> connection, String nodeId,
            Duration interval, Duration timeout) {
        if (interval.toMillis() < 1) {
            throw new IllegalArgumentException("heartbeat interval must be at least 1 ms, was " + interval);
        }
        if (timeout.compareTo(interval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "node timeout " + timeout + " must be at least twice the heartbeat interval " + interval);
        }
        this.connection = connection;
        this.nodeId = nodeId;
        this.interval = interval;
        this.timeout = timeout;
    }

    /** Nodes currently sharing the limits, this one included; never less than 1. Does no I/O. */
    public int nodeCount() {
        return nodeCount;
    }

    public String nodeId() {
        return nodeId;
    }

    /** Reports once straight away, so the count is usually right before the first request, then on a timer. */
    public synchronized void start() {
        if (closed || worker != null) {
            return;
        }
        beat();
        worker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("node-heartbeat").factory());
        worker.scheduleWithFixedDelay(this::beat, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One report. Never throws: a periodic task that throws is never run again. */
    void beat() {
        try {
            List<Long> result = HEARTBEAT.run(connection.get(), List.of(KEY), nodeId, Long.toString(timeout.toMillis()));
            int counted = (int) Math.max(1, result.get(0));
            lastSuccessNanos = System.nanoTime();
            if (warned) {
                warned = false;
                log.info("Reporting to Redis again");
            }
            if (counted != nodeCount) {
                log.info("Gateway nodes sharing the limits: {} -> {}", nodeCount, counted);
                nodeCount = counted;
            }
        } catch (RuntimeException e) {
            // A single slow reply is not worth a warning; being silent long enough to be dropped is.
            if (!warned && System.nanoTime() - lastSuccessNanos >= timeout.toNanos()) {
                warned = true;
                log.warn("Cannot report to Redis; still assuming {} gateway node(s): {}", nodeCount, e.toString());
            } else {
                log.debug("Heartbeat failed: {}", e.toString());
            }
        }
    }

    /** Stops reporting and tells the other nodes this one has gone. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (worker == null) {
            return;
        }
        worker.shutdown(); // a report in progress finishes first, so it cannot re-add the node afterwards
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            connection.get().sync().zrem(KEY, nodeId);
        } catch (RuntimeException e) {
            log.debug("Could not remove this node; it will be dropped after the node timeout: {}", e.toString());
        }
    }
}
