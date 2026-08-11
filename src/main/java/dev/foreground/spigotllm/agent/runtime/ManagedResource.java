package dev.foreground.spigotllm.agent.runtime;

/**
 * Hooks used by {@link ResourceRegistry} to manage an agent-created resource.
 * Implementations should make each operation idempotent.
 */
public interface ManagedResource {
    void enable() throws Exception;

    void disable() throws Exception;

    void close(RemovalReason reason) throws Exception;
}
