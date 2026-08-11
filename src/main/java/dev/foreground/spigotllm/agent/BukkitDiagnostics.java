package dev.foreground.spigotllm.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitWorker;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Version-portable structured diagnostics for the agent runtime. */
public final class BukkitDiagnostics {
    private final Server server;
    private final Path consoleLog;
    private final TickMonitor ticks;

    public BukkitDiagnostics(Server server, Path consoleLog, TickMonitor ticks) {
        this.server = server;
        this.consoleLog = consoleLog;
        this.ticks = ticks;
    }

    public JsonObject snapshot(String kind) {
        String value = kind == null ? "server" : kind.toLowerCase(Locale.ROOT);
        if ("server".equals(value)) return server();
        if ("players".equals(value)) return players();
        if ("worlds".equals(value)) return worlds();
        if ("plugins".equals(value)) return plugins();
        if ("memory".equals(value)) return memory();
        if ("scheduler".equals(value)) return scheduler();
        if ("ticks".equals(value) || "tick-health".equals(value)) return tickHealth();
        throw new IllegalArgumentException("Unknown snapshot kind: " + kind);
    }

    public JsonObject executeConsole(String command) {
        String clean = cleanCommand(command);
        boolean accepted = server.dispatchCommand(server.getConsoleSender(), clean);
        JsonObject result = new JsonObject();
        result.addProperty("accepted", accepted);
        result.addProperty("command", clean);
        result.addProperty("consoleLog", consoleLog.toString());
        return result;
    }

    public JsonObject sendMessage(String target, String message) {
        if (message == null || message.trim().isEmpty()) throw new IllegalArgumentException("Message cannot be empty.");
        JsonObject result = new JsonObject();
        if (target == null || "console".equalsIgnoreCase(target)) {
            server.getConsoleSender().sendMessage(message);
            result.addProperty("target", "console");
        } else if ("broadcast".equalsIgnoreCase(target)) {
            result.addProperty("recipients", server.broadcastMessage(message));
            result.addProperty("target", "broadcast");
        } else {
            Player player = server.getPlayerExact(target);
            if (player == null) throw new IllegalArgumentException("Online player not found: " + target);
            player.sendMessage(message);
            result.addProperty("target", player.getName());
            result.addProperty("uuid", player.getUniqueId().toString());
        }
        return result;
    }

    public JsonObject searchLog(String query, int maxLines, int maxBytes) throws IOException {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        int lineLimit = Math.max(1, Math.min(500, maxLines));
        int byteLimit = Math.max(4096, Math.min(4 * 1024 * 1024, maxBytes));
        List<String> matches = tailMatches(consoleLog, needle, lineLimit, byteLimit);
        JsonArray lines = new JsonArray();
        for (String line : matches) lines.add(line);
        JsonObject result = new JsonObject();
        result.addProperty("file", consoleLog.toString());
        result.addProperty("query", query == null ? "" : query);
        result.add("lines", lines);
        return result;
    }

    private JsonObject server() {
        JsonObject result = new JsonObject();
        result.addProperty("name", server.getName());
        result.addProperty("version", server.getVersion());
        result.addProperty("bukkitVersion", server.getBukkitVersion());
        result.addProperty("onlinePlayers", onlinePlayers().size());
        result.addProperty("maxPlayers", server.getMaxPlayers());
        result.addProperty("worlds", server.getWorlds().size());
        result.addProperty("primaryThread", Bukkit.isPrimaryThread());
        result.addProperty("onlineMode", server.getOnlineMode());
        return result;
    }

    private JsonObject players() {
        JsonArray values = new JsonArray();
        for (Player player : onlinePlayers()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", player.getName());
            item.addProperty("uuid", player.getUniqueId().toString());
            item.addProperty("op", player.isOp());
            item.addProperty("world", player.getWorld().getName());
            item.addProperty("health", player.getHealth());
            item.addProperty("gameMode", player.getGameMode().name());
            item.add("location", location(player.getLocation()));
            values.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("players", values);
        return result;
    }

    private JsonObject worlds() {
        JsonArray values = new JsonArray();
        for (World world : server.getWorlds()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", world.getName());
            item.addProperty("uuid", world.getUID().toString());
            item.addProperty("environment", world.getEnvironment().name());
            item.addProperty("time", world.getTime());
            item.addProperty("fullTime", world.getFullTime());
            item.addProperty("players", world.getPlayers().size());
            item.addProperty("entities", world.getEntities().size());
            Chunk[] loaded = world.getLoadedChunks();
            item.addProperty("loadedChunks", loaded == null ? 0 : loaded.length);
            values.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("worlds", values);
        return result;
    }

    private JsonObject plugins() {
        JsonArray values = new JsonArray();
        for (Plugin plugin : server.getPluginManager().getPlugins()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", plugin.getName());
            item.addProperty("version", plugin.getDescription().getVersion());
            item.addProperty("enabled", plugin.isEnabled());
            JsonArray authors = new JsonArray();
            for (String author : plugin.getDescription().getAuthors()) authors.add(author);
            item.add("authors", authors);
            values.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("plugins", values);
        return result;
    }

    private JsonObject memory() {
        Runtime runtime = Runtime.getRuntime();
        JsonObject result = new JsonObject();
        result.addProperty("freeBytes", runtime.freeMemory());
        result.addProperty("totalBytes", runtime.totalMemory());
        result.addProperty("maxBytes", runtime.maxMemory());
        result.addProperty("usedBytes", runtime.totalMemory() - runtime.freeMemory());
        result.addProperty("processors", runtime.availableProcessors());
        return result;
    }

    private JsonObject scheduler() {
        List<BukkitTask> pending = server.getScheduler().getPendingTasks();
        List<BukkitWorker> workers = server.getScheduler().getActiveWorkers();
        JsonObject result = new JsonObject();
        result.addProperty("pendingTasks", pending.size());
        result.addProperty("activeWorkers", workers.size());
        JsonObject owners = new JsonObject();
        for (BukkitTask task : pending) {
            String name = task.getOwner().getName();
            int count = owners.has(name) ? owners.get(name).getAsInt() : 0;
            owners.addProperty(name, count + 1);
        }
        result.add("pendingByPlugin", owners);
        return result;
    }

    private JsonObject tickHealth() {
        JsonObject result = new JsonObject();
        result.addProperty("tps5s", ticks.tps(100));
        result.addProperty("tps1m", ticks.tps(1200));
        result.addProperty("averageTickMillis5s", ticks.averageTickMillis(100));
        result.addProperty("averageTickMillis1m", ticks.averageTickMillis(1200));
        return result;
    }

    private Collection<? extends Player> onlinePlayers() {
        return server.getOnlinePlayers();
    }

    private JsonObject location(Location location) {
        JsonObject result = new JsonObject();
        result.addProperty("x", location.getX());
        result.addProperty("y", location.getY());
        result.addProperty("z", location.getZ());
        result.addProperty("yaw", location.getYaw());
        result.addProperty("pitch", location.getPitch());
        return result;
    }

    private String cleanCommand(String command) {
        if (command == null) throw new IllegalArgumentException("Command cannot be empty.");
        String clean = command.trim();
        while (clean.startsWith("/")) clean = clean.substring(1).trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Command cannot be empty.");
        for (int index = 0; index < clean.length(); index++) {
            if (Character.isISOControl(clean.charAt(index))) {
                throw new IllegalArgumentException("Command contains control characters.");
            }
        }
        return clean;
    }

    static List<String> tailMatches(Path file, String needle, int maxLines, int maxBytes) throws IOException {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) return Collections.emptyList();
        RandomAccessFile input = new RandomAccessFile(file.toFile(), "r");
        try {
            long length = input.length();
            long start = Math.max(0L, length - maxBytes);
            input.seek(start);
            if (start > 0L) input.readLine();
            List<String> result = new ArrayList<String>();
            String line;
            while ((line = input.readLine()) != null) {
                String decoded = new String(line.getBytes("ISO-8859-1"), "UTF-8");
                if (needle.isEmpty() || decoded.toLowerCase(Locale.ROOT).contains(needle)) {
                    result.add(decoded);
                    if (result.size() > maxLines) result.remove(0);
                }
            }
            return result;
        } finally {
            input.close();
        }
    }
}
