package io.axol.monkwatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

@Singleton
public class BridgeSocketServer {
    private static final int QUEUE_CAPACITY = 256;
    private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ObjectMapper mapper = new ObjectMapper();
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;
    private volatile boolean running;

    public synchronized void start(String path) throws IOException {
        if (running) return;
        Path sockPath = Path.of(path);
        Files.deleteIfExists(sockPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(sockPath));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "monkwatcher-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverChannel != null) serverChannel.close(); }
        catch (IOException ignored) {}
        if (acceptThread != null) acceptThread.interrupt();
    }

    private void acceptLoop() {
        while (running) {
            try {
                SocketChannel ch = serverChannel.accept();
                outbox.clear();
                Thread w = new Thread(() -> writeLoop(ch), "monkwatcher-write");
                w.setDaemon(true);
                w.start();
            } catch (IOException e) {
                if (running) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    private void writeLoop(SocketChannel ch) {
        try (var writer = Channels.newWriter(ch, StandardCharsets.UTF_8)) {
            while (running && ch.isOpen()) {
                String line = outbox.poll(1, TimeUnit.SECONDS);
                if (line == null) continue;
                writer.write(line);
                writer.write('\n');
                writer.flush();
            }
        } catch (Exception e) {
            // Consumer disconnected. acceptLoop will accept the next one.
        } finally {
            try { ch.close(); } catch (IOException ignored) {}
        }
    }

    public void send(TickPayload p) {
        try {
            outbox.offer(mapper.writeValueAsString(p));
        } catch (Exception ignored) {}
    }

    public void sendEvent(String type, Object data) {
        try {
            outbox.offer(mapper.writeValueAsString(new EventPayload(type, data)));
        } catch (Exception ignored) {}
    }
}
