package dev.foreground.spigotllm.agent.code;

/** A listener, command, or other resource tracked by a mini-module context. */
public interface MiniRegistration extends AutoCloseable {
    String id();

    boolean isActive();

    @Override
    void close();
}
