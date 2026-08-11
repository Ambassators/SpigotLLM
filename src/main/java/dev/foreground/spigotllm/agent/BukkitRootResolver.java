package dev.foreground.spigotllm.agent;

import com.google.gson.JsonObject;
import dev.foreground.spigotllm.agent.reflect.RootResolver;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.UUID;

/** Bukkit-backed roots and classloaders for the generic reflection service. */
public final class BukkitRootResolver implements RootResolver {
    private final Plugin host;

    public BukkitRootResolver(Plugin host) {
        this.host = host;
    }

    @Override
    public Object resolveRoot(String owner, String root, JsonObject arguments) {
        String type = root == null ? "" : root.toLowerCase(Locale.ROOT);
        Server server = host.getServer();
        if ("server".equals(type) || "bukkit".equals(type)) return server;
        if ("host".equals(type) || "spigotllm".equals(type)) return host;
        if ("console".equals(type)) return server.getConsoleSender();
        if ("scheduler".equals(type)) return server.getScheduler();
        if ("pluginManager".equalsIgnoreCase(root) || "plugins".equals(type)) return server.getPluginManager();
        if ("runtime".equals(type)) return Runtime.getRuntime();
        if ("thread".equals(type)) return Thread.currentThread();
        if ("plugin".equals(type)) {
            String name = string(arguments, "name");
            Plugin plugin = server.getPluginManager().getPlugin(name);
            if (plugin == null) throw new IllegalArgumentException("Plugin not found: " + name);
            return plugin;
        }
        if ("player".equals(type)) {
            String selector = string(arguments, "player");
            Player player;
            try { player = server.getPlayer(UUID.fromString(selector)); }
            catch (IllegalArgumentException ignored) { player = server.getPlayerExact(selector); }
            if (player == null) throw new IllegalArgumentException("Online player not found: " + selector);
            return player;
        }
        if ("world".equals(type)) {
            String selector = string(arguments, "world");
            World world;
            try { world = server.getWorld(UUID.fromString(selector)); }
            catch (IllegalArgumentException ignored) { world = server.getWorld(selector); }
            if (world == null) throw new IllegalArgumentException("World not found: " + selector);
            return world;
        }
        if ("classloader".equals(type)) return resolveClassLoader(owner, optional(arguments, "loader", "context"), arguments);
        throw new IllegalArgumentException("Unknown runtime root: " + root);
    }

    @Override
    public ClassLoader resolveClassLoader(String owner, String loader, JsonObject arguments) {
        String type = loader == null ? "context" : loader.toLowerCase(Locale.ROOT);
        if ("bootstrap".equals(type)) return null;
        if ("context".equals(type)) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            return context == null ? host.getClass().getClassLoader() : context;
        }
        if ("host".equals(type) || "spigotllm".equals(type)) return host.getClass().getClassLoader();
        if ("server".equals(type) || "bukkit".equals(type)) return host.getServer().getClass().getClassLoader();
        if ("plugin".equals(type)) {
            String name = string(arguments, "plugin");
            Plugin plugin = host.getServer().getPluginManager().getPlugin(name);
            if (plugin == null) throw new IllegalArgumentException("Plugin not found: " + name);
            return plugin.getClass().getClassLoader();
        }
        throw new IllegalArgumentException("Unknown classloader selector: " + loader);
    }

    private static String string(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return object.get(field).getAsString();
    }

    private static String optional(JsonObject object, String field, String fallback) {
        return object != null && object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : fallback;
    }
}
