package dev.foreground.spigotllm.model;

public enum SessionMode {
    CHAT("chat"),
    AGENT("agent");

    private final String id;

    SessionMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SessionMode parse(String input) {
        if (input == null) {
            return null;
        }
        for (SessionMode mode : values()) {
            if (mode.id.equalsIgnoreCase(input)) {
                return mode;
            }
        }
        return null;
    }
}
