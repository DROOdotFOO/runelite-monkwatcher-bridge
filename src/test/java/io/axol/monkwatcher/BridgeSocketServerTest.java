package io.axol.monkwatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BridgeSocketServerTest {

    private static final long PROPERTY_SEED = 0xCAFEBABE_DEADBEEFL;

    @Test(timeout = 5000)
    public void roundTripTickPayload() throws Exception {
        Path sockPath = tempSocketPath();
        BridgeSocketServer server = new BridgeSocketServer();
        try {
            server.start(sockPath.toString());

            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(sockPath));

                TickPayload p = new TickPayload();
                p.t = 42L;
                p.tick = 100;
                p.hp = 99;

                // Burst-send to step past the accept-time outbox.clear() race
                // (see ADR-0003, ADR-0005). One send could lose the race; 50 cannot.
                for (int i = 0; i < 50; i++) {
                    server.send(p);
                }

                BufferedReader reader = new BufferedReader(
                    Channels.newReader(client, StandardCharsets.UTF_8));
                String line = reader.readLine();

                assertNotNull("expected a JSON line from server", line);
                assertTrue("expected v field in: " + line, line.contains("\"v\":1"));
                assertTrue("expected tick field in: " + line, line.contains("\"tick\":100"));
                assertTrue("expected hp field in: " + line, line.contains("\"hp\":99"));
            }
        } finally {
            server.stop();
            Files.deleteIfExists(sockPath);
        }
    }

    @Test(timeout = 5000)
    public void roundTripEventPayloadWithNullData() throws Exception {
        Path sockPath = tempSocketPath();
        BridgeSocketServer server = new BridgeSocketServer();
        try {
            server.start(sockPath.toString());

            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(sockPath));

                for (int i = 0; i < 50; i++) {
                    server.sendEvent("player_death", null);
                }

                BufferedReader reader = new BufferedReader(
                    Channels.newReader(client, StandardCharsets.UTF_8));
                String line = reader.readLine();

                assertNotNull(line);
                assertTrue("expected v field in: " + line, line.contains("\"v\":1"));
                assertTrue("expected event field in: " + line, line.contains("\"event\":\"player_death\""));
                // ADR-0004: null data must serialize explicitly so the consumer can
                // distinguish `player_death` (null data) from event types it has not seen.
                assertTrue("expected explicit data:null in: " + line, line.contains("\"data\":null"));
            }
        } finally {
            server.stop();
            Files.deleteIfExists(sockPath);
        }
    }

    @Test(timeout = 3000)
    public void sendNeverBlocksRegardlessOfBurstSize() {
        // ADR-0003: send() must not block the game thread, ever. With offer() the queue
        // drops on overflow; with put() it would block at 256. If anyone swaps offer for
        // put, this test hangs and JUnit kills it via @Test(timeout).
        BridgeSocketServer server = new BridgeSocketServer();
        TickPayload p = new TickPayload();
        p.tick = 1;

        Random rng = new Random(PROPERTY_SEED);
        for (int trial = 0; trial < 100; trial++) {
            int burst = rng.nextInt(1000) + 1;
            for (int i = 0; i < burst; i++) {
                server.send(p);
            }
        }
        // Reaching here within 3s proves send() does not block on a full queue.
    }

    @Test
    public void outboxBoundedAtCapacityAdr0003() throws Exception {
        // ADR-0003: outbox is bounded at 256, regardless of producer pressure.
        // Probes the internal queue via reflection -- the public surface intentionally
        // exposes no size accessor, but this invariant is the most load-bearing decision
        // in the codebase and deserves direct verification.
        BridgeSocketServer server = new BridgeSocketServer();
        TickPayload p = new TickPayload();
        p.tick = 1;

        Random rng = new Random(PROPERTY_SEED);
        for (int trial = 0; trial < 50; trial++) {
            int burst = rng.nextInt(2000) + 1;
            for (int i = 0; i < burst; i++) {
                server.send(p);
            }
            int size = outboxSize(server);
            assertTrue("trial " + trial + ": outbox size " + size + " exceeded 256 cap",
                size <= 256);
        }
    }

    @Test(timeout = 5000)
    public void newConsumerSeesFreshStateNotBacklog() throws Exception {
        // ADR-0005: outbox.clear() runs on every accept so a fresh consumer sees
        // fresh state, not whatever was queued for the previous one.
        final int STALE_TICK = 11111;
        final int MARKER_TICK = 22222;

        Path sockPath = tempSocketPath();
        BridgeSocketServer server = new BridgeSocketServer();
        ObjectMapper mapper = new ObjectMapper();
        try {
            server.start(sockPath.toString());

            // Stage backlog into the outbox with no consumer connected.
            TickPayload stale = new TickPayload();
            stale.tick = STALE_TICK;
            for (int i = 0; i < 256; i++) {
                server.send(stale);
            }

            // Connect a consumer -- accept-time outbox.clear() should wipe the backlog.
            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(sockPath));

                // Pump marker sends in the background to defuse the clear-vs-send race
                // (a single send timed wrong could land before clear() and be wiped).
                TickPayload marker = new TickPayload();
                marker.tick = MARKER_TICK;
                Thread pump = new Thread(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (!Thread.currentThread().isInterrupted()
                            && System.nanoTime() < deadline) {
                        server.send(marker);
                    }
                }, "marker-pump");
                pump.setDaemon(true);
                pump.start();

                try {
                    BufferedReader reader = new BufferedReader(
                        Channels.newReader(client, StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    assertNotNull(line);
                    JsonNode root = mapper.readTree(line);

                    // If clear() were broken, we would see STALE_TICK first because
                    // stale messages sit at the head of the queue.
                    assertEquals(
                        "first line must be a marker; saw stale tick means clear() is broken: " + line,
                        MARKER_TICK, root.get("tick").asInt());
                } finally {
                    pump.interrupt();
                }
            }
        } finally {
            server.stop();
            Files.deleteIfExists(sockPath);
        }
    }

    @Test(timeout = 5000)
    public void stopClosesServerChannel() throws Exception {
        // Verifies the cleanup hygiene in stop(): the server channel is closed and the
        // accept thread is interrupted. Without close(), a long-running plugin that
        // gets enabled/disabled repeatedly would leak file descriptors.
        Path sockPath = tempSocketPath();
        BridgeSocketServer server = new BridgeSocketServer();
        try {
            server.start(sockPath.toString());
            server.stop();

            java.nio.channels.ServerSocketChannel channel = reflectServerChannel(server);
            org.junit.Assert.assertFalse(
                "stop() must close the server channel", channel.isOpen());
        } finally {
            Files.deleteIfExists(sockPath);
        }
    }

    @Test(timeout = 5000)
    public void acceptThreadIsDaemon() throws Exception {
        // Background threads in this plugin must be daemons so they don't keep the
        // JVM alive after RuneLite shuts down. The accept thread is the long-running
        // listener; verifying its flag pins the architectural choice.
        //
        // Subtlety: a new Thread inherits the daemon flag from its creator. PIT runs
        // tests under a worker thread that is itself a daemon, so a naive assertion
        // passes whether or not setDaemon(true) is called. We start the server from
        // an explicit non-daemon helper thread so the daemon assertion is meaningful.
        Path sockPath = tempSocketPath();
        BridgeSocketServer server = new BridgeSocketServer();
        java.util.concurrent.atomic.AtomicReference<Boolean> daemonFlag =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> error =
            new java.util.concurrent.atomic.AtomicReference<>();

        Thread starter = new Thread(() -> {
            try {
                server.start(sockPath.toString());
                daemonFlag.set(reflectAcceptThread(server).isDaemon());
            } catch (Exception e) {
                error.set(e);
            }
        }, "non-daemon-starter");
        starter.setDaemon(false);

        try {
            starter.start();
            starter.join(3000);
            if (error.get() != null) throw error.get();
            assertNotNull("starter did not capture the daemon flag", daemonFlag.get());
            assertTrue("accept thread must be daemon", daemonFlag.get());
        } finally {
            server.stop();
            Files.deleteIfExists(sockPath);
        }
    }

    @Test(timeout = 5000)
    public void stopTerminatesAcceptThread() throws Exception {
        // Verifies that stop() actually winds down the accept thread within bounded
        // time. RuneLite calls Plugin.shutDown() when the user disables the plugin or
        // closes the client; a stuck accept thread holding a UDS bind would block the
        // next start() from re-binding to the same path.
        Path sockPath = tempSocketPath();
        BridgeSocketServer server = new BridgeSocketServer();
        try {
            server.start(sockPath.toString());
            Thread acceptThread = reflectAcceptThread(server);
            assertTrue("precondition: accept thread should be alive after start",
                acceptThread.isAlive());

            server.stop();
            acceptThread.join(2000);
            org.junit.Assert.assertFalse(
                "accept thread must terminate within 2s of stop()", acceptThread.isAlive());
        } finally {
            Files.deleteIfExists(sockPath);
        }
    }

    private static int outboxSize(BridgeSocketServer server) throws Exception {
        Field f = BridgeSocketServer.class.getDeclaredField("outbox");
        f.setAccessible(true);
        return ((BlockingQueue<?>) f.get(server)).size();
    }

    private static java.nio.channels.ServerSocketChannel reflectServerChannel(
            BridgeSocketServer server) throws Exception {
        Field f = BridgeSocketServer.class.getDeclaredField("serverChannel");
        f.setAccessible(true);
        return (java.nio.channels.ServerSocketChannel) f.get(server);
    }

    private static Thread reflectAcceptThread(BridgeSocketServer server) throws Exception {
        Field f = BridgeSocketServer.class.getDeclaredField("acceptThread");
        f.setAccessible(true);
        return (Thread) f.get(server);
    }

    private static Path tempSocketPath() {
        // Use /tmp directly: macOS sun_path is capped at 104 chars and the default
        // java.io.tmpdir (/var/folders/xx/.../T/) eats ~50 of those before we even add
        // a filename. /tmp is short and exists on both macOS and Linux.
        return Path.of("/tmp", "mw-test-" + UUID.randomUUID().toString().substring(0, 12) + ".sock");
    }
}
