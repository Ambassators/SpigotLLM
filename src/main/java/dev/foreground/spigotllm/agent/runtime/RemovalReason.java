package dev.foreground.spigotllm.agent.runtime;

/** Explains why a resource is being permanently detached from the live registry. */
public enum RemovalReason {
    EXPLICIT,
    PROMPT_CLOSED,
    EXPIRED,
    OWNER_PURGED,
    SHUTDOWN
}
