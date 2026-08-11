package dev.foreground.spigotllm.agent.code;

/** Receives values emitted by dynamically compiled code. */
public interface MiniEmitter {
    void emit(Object value);
}
