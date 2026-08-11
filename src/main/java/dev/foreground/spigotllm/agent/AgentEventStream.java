package dev.foreground.spigotllm.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** Bounded, non-blocking event writer shared by every active agent lease. */
public final class AgentEventStream implements AutoCloseable {
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Logger logger;
    private final long maxBytes;
    private final LinkedBlockingQueue<Record> queue;
    private final Map<Path, AtomicLong> dropped = new ConcurrentHashMap<Path, AtomicLong>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final ExecutorService writer;

    public AgentEventStream(Logger logger, int maxQueuedEvents, long maxBytes) {
        this.logger = logger;
        this.maxBytes = Math.max(64L * 1024L, maxBytes);
        this.queue = new LinkedBlockingQueue<Record>(Math.max(100, maxQueuedEvents));
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpigotLLM-agent-events");
            thread.setDaemon(true);
            return thread;
        });
        writer.execute(this::writeLoop);
    }

    public void emit(Path file, JsonObject event) {
        if (!open.get() || file == null || event == null) return;
        JsonObject copy = event.deepCopy();
        copy.addProperty("timestamp", System.currentTimeMillis());
        Record record = new Record(file.toAbsolutePath().normalize(), gson.toJson(copy) + "\n");
        if (!queue.offer(record)) {
            dropped.computeIfAbsent(record.file, ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    private void writeLoop() {
        while (open.get() || !queue.isEmpty()) {
            try {
                Record record = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (record != null) write(record);
            } catch (InterruptedException e) {
                if (!open.get()) break;
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                logger.warning("Could not write an agent runtime event: " + e.getMessage());
            }
        }
    }

    private void write(Record record) throws IOException {
        Files.createDirectories(record.file.getParent());
        if (Files.isRegularFile(record.file) && Files.size(record.file) >= maxBytes) {
            Path previous = record.file.resolveSibling(record.file.getFileName().toString() + ".1");
            Files.move(record.file, previous, StandardCopyOption.REPLACE_EXISTING);
        }
        AtomicLong counter = dropped.get(record.file);
        long count = counter == null ? 0L : counter.getAndSet(0L);
        if (count > 0L) {
            JsonObject warning = new JsonObject();
            warning.addProperty("type", "events.dropped");
            warning.addProperty("count", count);
            warning.addProperty("timestamp", System.currentTimeMillis());
            Files.write(record.file, (gson.toJson(warning) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.write(record.file, record.line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3L, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private static final class Record {
        final Path file;
        final String line;

        Record(Path file, String line) {
            this.file = file;
            this.line = line;
        }
    }
}
