package dev.foreground.spigotllm.agent.reflect;

import com.google.gson.JsonObject;

/**
 * Supplies server-specific root objects and class loaders to the otherwise
 * Bukkit-independent reflection service.
 */
public interface RootResolver {
    /**
     * Resolve a named root such as {@code server}, {@code plugin},
     * {@code player}, or {@code world}. The complete arguments object is
     * supplied so implementations can consume selectors such as a plugin or
     * player name.
     */
    Object resolveRoot(String owner, String root, JsonObject arguments) throws Exception;

    /**
     * Resolve the loader used by {@code reflect.class}. Returning {@code null}
     * selects the bootstrap class loader.
     */
    ClassLoader resolveClassLoader(String owner, String loader, JsonObject arguments) throws Exception;
}
