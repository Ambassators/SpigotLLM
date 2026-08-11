package dev.foreground.spigotllm.console;

import com.google.gson.JsonObject;
import dev.foreground.spigotllm.agent.AgentToolRuntime;
import dev.foreground.spigotllm.agent.runtime.AgentProtocol;
import dev.foreground.spigotllm.agent.runtime.AgentRequest;
import dev.foreground.spigotllm.agent.runtime.ProtocolException;
import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.session.SessionRecord;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.DirectoryStream;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;
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
    private final int maxRequestBytes;
    private final RuntimeAdapter toolRuntime;
    private final Map<String, Lease> active = new ConcurrentHashMap<String, Lease>();
    private final BukkitTask pollTask;

    public AgentConsoleBridge(JavaPlugin plugin, Path root, Path consoleLog,
                              int pollTicks, int maxCommandLength,
                              int maxRequestBytes, AgentToolRuntime toolRuntime) throws IOException {
        this(plugin, root, consoleLog, pollTicks, maxCommandLength, maxRequestBytes,
                runtimeAdapter(toolRuntime), true);
    }

    AgentConsoleBridge(Path root, Path consoleLog, int maxCommandLength,
                       int maxRequestBytes, RuntimeAdapter toolRuntime) throws IOException {
        this(null, root, consoleLog, 1, maxCommandLength, maxRequestBytes, toolRuntime, false);
    }

    private AgentConsoleBridge(JavaPlugin plugin, Path root, Path consoleLog,
                               int pollTicks, int maxCommandLength, int maxRequestBytes,
                               RuntimeAdapter toolRuntime, boolean schedulePolling) throws IOException {
        if (toolRuntime == null) throw new IllegalArgumentException("Tool runtime is required.");
        this.plugin = plugin;
        this.root = root.toAbsolutePath().normalize();
        this.consoleLog = consoleLog.toAbsolutePath().normalize();
        this.maxCommandLength = Math.max(64, Math.min(8192, maxCommandLength));
        this.maxRequestBytes = Math.max(1024, Math.min(AgentProtocol.MAX_REQUEST_BYTES, maxRequestBytes));
        this.toolRuntime = toolRuntime;
        Files.createDirectories(this.root);
        this.pollTask = schedulePolling ? Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { poll(); }
        }, 1L, Math.max(1, pollTicks)) : null;
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
            Files.createDirectories(lease.requestsDirectory);
            Files.createDirectories(lease.responsesDirectory);
            clearJsonFiles(lease.requestsDirectory);
            clearJsonFiles(lease.responsesDirectory);
            writeAtomic(directory.resolve("README.txt"), lease.guide());
            writeApiGuide(directory.resolve("API.md"));
            return lease;
        } catch (IOException e) {
            active.remove(key, lease);
            throw e;
        }
    }

    public void shutdown() {
        if (pollTask != null) pollTask.cancel();
        for (Lease lease : active.values()) lease.close();
        active.clear();
    }

    private void poll() {
        toolRuntime.tick();
        for (Lease lease : active.values()) {
            try {
                process(lease);
                processJsonRequests(lease);
            } catch (IOException e) {
                plugin.getLogger().warning("Agent console bridge failed for " + lease.key + ": " + e.getMessage());
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Agent console command failed for " + lease.key + ": "
                        + e.getClass().getSimpleName());
                try {
                    Files.deleteIfExists(lease.request);
                    writeAtomic(lease.response, "status=error\nmessage=Command dispatch failed: "
                            + safeMessage(e.getMessage()) + "\n");
                } catch (IOException ignored) { }
            }
        }
    }

    void processJsonRequests(Lease lease) throws IOException {
        if (!lease.active() || !Files.isDirectory(lease.requestsDirectory)) return;
        int processed = 0;
        try (DirectoryStream<Path> requests = Files.newDirectoryStream(lease.requestsDirectory, "*.json")) {
            for (Path requestFile : requests) {
                if (processed++ >= 16 || !lease.active()) break;
                Path normalized = requestFile.toAbsolutePath().normalize();
                if (!normalized.startsWith(lease.requestsDirectory)) continue;
                String fileName = normalized.getFileName().toString();
                String fileId = fileName.substring(0, fileName.length() - 5);
                byte[] bytes;
                try {
                    long size = Files.size(normalized);
                    if (size > maxRequestBytes) {
                        Files.deleteIfExists(normalized);
                        writeJsonResponse(lease, fileId,
                                AgentProtocol.error(fileId, "request_too_large", "Request exceeds the configured byte limit."));
                        continue;
                    }
                    bytes = Files.readAllBytes(normalized);
                } catch (IOException e) {
                    continue;
                }
                AgentRequest request;
                try {
                    request = AgentProtocol.parse(bytes);
                    if (!fileId.equals(request.getId())) {
                        Files.deleteIfExists(normalized);
                        writeJsonResponse(lease, fileId, AgentProtocol.error(fileId, "id_mismatch",
                                "The request id must match its filename."));
                        continue;
                    }
                } catch (ProtocolException e) {
                    Files.deleteIfExists(normalized);
                    writeJsonResponse(lease, fileId, AgentProtocol.error(e));
                    continue;
                }
                if (!lease.claimRequest(request.getId())) {
                    Files.deleteIfExists(normalized);
                    writeJsonResponse(lease, fileId, AgentProtocol.error(fileId, "duplicate_id",
                            "That request id has already been used in this lease."));
                    continue;
                }
                Files.deleteIfExists(normalized);
                toolRuntime.submit(lease, request);
            }
        }
    }

    private void writeJsonResponse(Lease lease, String fallbackId, JsonObject response) throws IOException {
        String id = response.has("id") && !response.get("id").isJsonNull()
                ? response.get("id").getAsString() : fallbackId;
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) return;
        writeAtomic(lease.responsesDirectory.resolve(id + ".json"), response.toString() + "\n");
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
                    + lease.session.getProvider().id() + "/" + lease.session.getName() + ": "
                    + commandName(parsed.command));
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

    static void writeAtomic(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temporary, value.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String commandName(String command) {
        if (command == null) return "";
        String trimmed = command.trim();
        int separator = trimmed.indexOf(' ');
        return separator < 0 ? trimmed : trimmed.substring(0, separator);
    }

    private static void clearJsonFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                Path normalized = file.toAbsolutePath().normalize();
                if (normalized.getParent().equals(directory.toAbsolutePath().normalize())) Files.deleteIfExists(normalized);
            }
        }
    }

    private void writeApiGuide(Path target) throws IOException {
        if (plugin == null) return;
        InputStream input = plugin.getResource("agent-tools-guide.md");
        if (input == null) return;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            writeAtomic(target, new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            input.close();
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

    interface RuntimeAdapter {
        void tick();
        void submit(AgentToolRuntime.LeaseAccess lease, AgentRequest request);
        void closePrompt(String owner, String promptScope, String handleOwner);
    }

    private static RuntimeAdapter runtimeAdapter(final AgentToolRuntime runtime) {
        if (runtime == null) throw new IllegalArgumentException("Tool runtime is required.");
        return new RuntimeAdapter() {
            @Override public void tick() { runtime.tick(); }
            @Override public void submit(AgentToolRuntime.LeaseAccess lease, AgentRequest request) {
                runtime.submit(lease, request);
            }
            @Override public void closePrompt(String owner, String promptScope, String handleOwner) {
                runtime.closePrompt(owner, promptScope, handleOwner);
            }
        };
    }

    public final class Lease implements AutoCloseable, AgentToolRuntime.LeaseAccess {
        private final String key;
        private final Identity identity;
        private final SessionRecord session;
        private final Path directory;
        private final Path request;
        private final Path response;
        private final Path requestsDirectory;
        private final Path responsesDirectory;
        private final Path eventsFile;
        private final String promptScope;
        private final Path leaseDirectory;
        private volatile boolean leaseActive = true;
        private final Set<String> processedRequests = Collections.synchronizedSet(new HashSet<String>());

        private Lease(String key, Identity identity, SessionRecord session,
                      Path directory, Path request, Path response) {
            this.key = key;
            this.identity = identity;
            this.session = session;
            this.directory = directory;
            this.request = request;
            this.response = response;
            this.promptScope = UUID.randomUUID().toString();
            this.leaseDirectory = directory.resolve("leases").resolve(promptScope).toAbsolutePath().normalize();
            this.requestsDirectory = leaseDirectory.resolve("requests").toAbsolutePath().normalize();
            this.responsesDirectory = leaseDirectory.resolve("responses").toAbsolutePath().normalize();
            this.eventsFile = leaseDirectory.resolve("events.jsonl").toAbsolutePath().normalize();
        }

        public String instructions() {
            return "Minecraft console access is available for this agent session. Read and follow the bridge "
                    + "instructions at " + directory.resolve("README.txt") + ".";
        }

        private String guide() {
            return "MINECRAFT AGENT RUNTIME BRIDGE\n\n"
                    + "This bridge has the full authority of the real Minecraft server console. "
                    + "Use it only when the user's task requires it.\n\n"
                    + "READ CONSOLE\nRead the live UTF-8 server log at:\n" + consoleLog + "\n\n"
                    + "RUN A CONSOLE COMMAND\nWrite one UTF-8 line ending in a newline to:\n" + request + "\n\n"
                    + "The line format is: <unique-request-id><TAB><command-without-leading-slash>\n"
                    + "Example: req-1<TAB>list\n\n"
                    + "Then wait for a response with the same id at:\n" + response + "\n"
                    + "After an accepted command, read the live console log for its output. "
                    + "Submit only one legacy command request at a time.\n\n"
                    + "JSON TOOL REQUESTS\nAtomically write UTF-8 JSON request files to:\n"
                    + requestsDirectory + "\nThe filename is <id>.json and its envelope is:\n"
                    + "{\"id\":\"req-1\",\"operation\":\"snapshot.server\",\"arguments\":{},\"lifecycle\":\"prompt\"}\n"
                    + "Read the matching response at:\n" + responsesDirectory + "\n"
                    + "Runtime events and emitted values are appended to:\n" + eventsFile + "\n\n"
                    + "Available families: snapshot.*, log.search, console.execute, message.send, "
                    + "event.watch/event.await/event.unwatch, command.create/command.remove, "
                    + "schedule.once/schedule.repeat/schedule.cancel, code.compile/code.run/code.runLater/"
                    + "code.runTimer/code.cancel, reflect.*, module.*, and resource.*. "
                    + "Read the complete schemas and examples at " + directory.resolve("API.md") + ".\n";
        }

        /** Resource/handle ownership is isolated to this stable provider thread, not just the human identity. */
        @Override public String owner() { return identity.key() + "|" + session.key(); }
        @Override public String promptScope() { return promptScope; }
        @Override public String handleOwner() { return identity.key() + "|" + promptScope; }
        @Override public Path responsesDirectory() { return responsesDirectory; }
        @Override public Path eventsFile() { return eventsFile; }
        @Override public boolean active() { return leaseActive && active.get(key) == this; }
        private boolean claimRequest(String id) { return processedRequests.add(id); }

        @Override
        public void close() {
            synchronized (this) {
                if (!active.remove(key, this)) return;
                leaseActive = false;
                toolRuntime.closePrompt(owner(), promptScope, handleOwner());
                try {
                    Files.deleteIfExists(request);
                    writeAtomic(directory.resolve("bridge.closed"), "closed\n");
                } catch (IOException ignored) { }
            }
        }
    }
}
