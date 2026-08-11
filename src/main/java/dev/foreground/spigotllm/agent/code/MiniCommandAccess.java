package dev.foreground.spigotllm.agent.code;

/** Access policies understood by the runtime command registrar. */
public enum MiniCommandAccess {
    CONSOLE,
    PLAYERS,
    OPS,
    AUTHORIZED_OPERATORS,
    PERMISSION,
    EVERYONE
}
