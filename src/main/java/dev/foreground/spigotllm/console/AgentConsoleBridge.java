package dev.foreground.spigotllm.console;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.session.SessionRecord;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Gives active provider agents a filesystem request channel into Bukkit's real
 * console. Requests are always dispatched by the synchronous scheduler.
 */
public final class AgentConsoleBridge {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JavaPlugin plugin;
    private final Path root;
    private final Path consoleLog;
    private final int maxCommandLength;
    private final Map<String, Lease> active = new ConcurrentHashMap<String, Lease>();
    private final BukkitTask pollTask;

    public AgentConsoleBridge(JavaPlugin plugin, Path root, Path consoleLog,
                              int pollTicks, int maxCommandLength) throws IOException {
        this.plugin = plugin;
        this.root = root.toAbsolutePath().normalize();
        this.consoleLog = consoleLog.toAbsolutePath().normalize();
        this.maxCommandLength = Math.max(64, Math.min(8192, maxCommandLength));
        Files.createDirectories(this.root);
        this.pollTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                poll();
            }
        }, 1L, Math.max(1, pollTicks));
    }

    public Lease open(Identity identity, SessionRecord session) throws IOException {
        String key = identity.key() + "|" + session.key();
        Path directory = root.resolve(identity.key())
                .resolve(session.getProvider().id() + "-" + session.getMode().id() + "-" + session.getStorageId())
                .normalize();
        if (!directory.startsWith(root)) throw new IOException("Invalid agent console bridge path");
        Files.createDirectories(directory);
        Path request = directory.resolve("command.request");
        Path response = directory.resolve("command.response");
        Lease lease = new Lease(key, identity, session, directory, request, response);
        Lease previous = active.putIfAbsent(key, lease);
        if (previous != null) throw new IOException("That thread already has an active console bridge");
        try {
            Files.deleteIfExists(request);
            Files.deleteIfExists(response);
            Files.deleteIfExists(directory.resolve("bridge.closed"));
            writeAtomic(directory.resolve("README.txt"), lease.guide());
            return lease;
        } catch (IOException e) {
            active.remove(key, lease);
            throw e;
        }
    }

    public void shutdown() {
        pollTask.cancel();
        for (Lease lease : active.values()) lease.close();
        active.clear();
    }

    private void poll() {
        for (Lease lease : active.values()) {
            try {
                process(lease);
            } catch (IOException e) {
                plugin.getLogger().warning("Agent console bridge failed for " + lease.key + ": " + e.getMessage());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Agent console command failed for " + lease.key + ": "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                try {
                    Files.deleteIfExists(lease.request);
                    writeAtomic(lease.response, "status=error\nmessage=Command dispatch failed: "
                            + safeMessage(e.getMessage()) + "\n");
                } catch (IOException ignored) { }
            }
        }
    }

    private void process(Lease lease) throws IOException {
        synchronized (lease) {
            if (active.get(lease.key) != lease || !Files.isRegularFile(lease.request)) return;
            long size = Files.size(lease.request);
            if (size > maxCommandLength + 128L) {
                Files.deleteIfExists(lease.request);
                writeAtomic(lease.response, "status=error\nmessage=Command request is too large.\n");
                return;
            }
            String raw = new String(Files.readAllBytes(lease.request), StandardCharsets.UTF_8);
            ParsedRequest parsed;
            try {
                parsed = parseRequest(raw, maxCommandLength);
            } catch (IllegalArgumentException e) {
                Files.deleteIfExists(lease.request);
                writeAtomic(lease.response, "status=error\nmessage=" + safeMessage(e.getMessage()) + "\n");
                return;
            }
            if (parsed == null) return;

            Files.deleteIfExists(lease.request);
            Files.deleteIfExists(lease.response);
            plugin.getLogger().warning("Agent console command by " + lease.identity.displayName() + " using "
                    + lease.session.getProvider().id() + "/" + lease.session.getName() + ": " + parsed.command);
            boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed.command);
            writeAtomic(lease.response, "id=" + parsed.id + "\nstatus="
                    + (accepted ? "dispatched" : "rejected") + "\ncommand=" + parsed.command
                    + "\nconsole-log=" + consoleLog + "\n");
        }
    }

    static ParsedRequest parseRequest(String raw, int maxCommandLength) {
        if (raw == null || raw.isEmpty() || !(raw.endsWith("\n") || raw.endsWith("\r"))) return null;
        String line = raw.replace("\r\n", "\n");
        while (line.endsWith("\n") || line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Only one command may be submitted per request.");
        }
        int separator = line.indexOf('\t');
        if (separator < 1) throw new IllegalArgumentException("Use <request-id><TAB><command>.");
        String id = line.substring(0, separator).trim();
        if (!REQUEST_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid request id.");
        String command = line.substring(separator + 1).trim();
        while (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty()) throw new IllegalArgumentException("Console command cannot be empty.");
        if (command.length() > maxCommandLength) throw new IllegalArgumentException("Console command is too long.");
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("Console command contains control characters.");
            }
        }
        return new ParsedRequest(id, command);
    }

    private static String safeMessage(String message) {
        if (message == null || message.trim().isEmpty()) return "Unknown error.";
        return message.replace('\r', ' ').replace('\n', ' ').replace('=', ':').trim();
    }

    private static void writeAtomic(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temporary, value.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class ParsedRequest {
        final String id;
        final String command;

        ParsedRequest(String id, String command) {
            this.id = id;
            this.command = command;
        }
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final Identity identity;
        private final SessionRecord session;
        private final Path directory;
        private final Path request;
        private final Path response;

        private Lease(String key, Identity identity, SessionRecord session,
                      Path directory, Path request, Path response) {
            this.key = key;
            this.identity = identity;
            this.session = session;
            this.directory = directory;
            this.request = request;
            this.response = response;
        }

        public String instructions() {
            return "Minecraft console access is available for this agent session. Read and follow the bridge "
                    + "instructions at " + directory.resolve("README.txt") + ".";
        }

        private String guide() {
            return "MINECRAFT CONSOLE BRIDGE\n\n"
                    + "This bridge has the full authority of the real Minecraft server console. "
                    + "Use it only when the user's task requires it.\n\n"
                    + "READ CONSOLE\nRead the live UTF-8 server log at:\n" + consoleLog + "\n\n"
                    + "RUN A CONSOLE COMMAND\nWrite one UTF-8 line ending in a newline to:\n" + request + "\n\n"
                    + "The line format is: <unique-request-id><TAB><command-without-leading-slash>\n"
                    + "Example: req-1<TAB>list\n\n"
                    + "Then wait for a response with the same id at:\n" + response + "\n"
                    + "After an accepted command, read the live console log for its output. "
                    + "Submit only one request at a time.\n";
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!active.remove(key, this)) return;
                try {
                    Files.deleteIfExists(request);
                    writeAtomic(directory.resolve("bridge.closed"), "closed\n");
                } catch (IOException ignored) { }
            }
        }
    }
}
