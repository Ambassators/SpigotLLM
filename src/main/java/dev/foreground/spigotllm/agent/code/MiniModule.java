package dev.foreground.spigotllm.agent.code;

/** A dynamically compiled unit whose registrations are owned by its context. */
public interface MiniModule {
    void onEnable(MiniContext context) throws Exception;

    void onDisable() throws Exception;
}
