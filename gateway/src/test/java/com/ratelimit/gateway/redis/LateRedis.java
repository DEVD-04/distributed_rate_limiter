package com.ratelimit.gateway.redis;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import org.testcontainers.containers.GenericContainer;

/**
 * A Redis whose address is known before it exists, for tests of a gateway starting without Redis, and
 * which keeps that address across a real stop and start.
 */
public final class LateRedis implements AutoCloseable {

    private final int port = freePort();
    private final GenericContainer<?> container = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), ExposedPort.tcp(6379))));

    public String url() {
        return "redis://127.0.0.1:" + port;
    }

    public void start() {
        container.start();
    }

    /** Stops the server, as an outage would; the container (and its port) remain. */
    public void stopServer() {
        container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(0).exec();
    }

    public void startServerAgain() {
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
    }

    /** Freezes the server: connections stay open and nothing is answered, as with a stalled Redis or network. */
    public void freeze() {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    public void unfreeze() {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }

    @Override
    public void close() {
        container.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
