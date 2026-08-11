package dev.foreground.spigotllm.agent.code;

import org.bukkit.event.Event;

public interface MiniEventHandler<T extends Event> {
    void handle(T event) throws Exception;
}
