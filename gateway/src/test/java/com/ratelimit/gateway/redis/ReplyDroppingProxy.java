package com.ratelimit.gateway.redis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sits between a client and Redis and can cause the worst kind of network failure: the next script call
 * reaches Redis and runs, then the connection breaks before the reply arrives. Later connections work.
 */
final class ReplyDroppingProxy implements AutoCloseable {

    private final ServerSocket server;
    private final String redisHost;
    private final int redisPort;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean armed = new AtomicBoolean();
    final AtomicInteger dropped = new AtomicInteger();
    private volatile String subject;

    /** Remembers which subject the test is working on. */
    ReplyDroppingProxy forSubject(String name) {
        this.subject = name;
        return this;
    }

    String subject() {
        return subject;
    }

    ReplyDroppingProxy(String redisHost, int redisPort) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        try {
            this.server = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Thread.ofPlatform().daemon().start(this::accept);
    }

    String url() {
        return "redis://127.0.0.1:" + server.getLocalPort();
    }

    void dropTheReplyToTheNextScriptCall() {
        armed.set(true);
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Socket redis = new Socket(redisHost, redisPort);
                sockets.add(client);
                sockets.add(redis);
                var dropNextReply = new AtomicBoolean();
                Thread.ofPlatform().daemon().start(() -> requests(client, redis, dropNextReply));
                Thread.ofPlatform().daemon().start(() -> replies(redis, client, dropNextReply));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void requests(Socket client, Socket redis, AtomicBoolean dropNextReply) {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = client.getInputStream(); OutputStream out = redis.getOutputStream()) {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                if (new String(buffer, 0, n, StandardCharsets.ISO_8859_1).contains("EVALSHA") && armed.compareAndSet(true, false)) {
                    dropNextReply.set(true); // set before forwarding, so the reply cannot slip past
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // one side went away
        } finally {
            close(client, redis);
        }
    }

    private void replies(Socket redis, Socket client, AtomicBoolean dropNextReply) {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = redis.getInputStream(); OutputStream out = client.getOutputStream()) {
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                if (dropNextReply.get()) {
                    dropped.incrementAndGet(); // a reply exists, so Redis ran the call
                    return;
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // one side went away
        } finally {
            close(client, redis);
        }
    }

    private static void close(Socket... both) {
        for (Socket socket : both) {
            try {
                socket.close();
            } catch (IOException e) {
                // already closed
            }
        }
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException e) {
            // nothing to do
        }
        close(sockets.toArray(Socket[]::new));
    }
}
