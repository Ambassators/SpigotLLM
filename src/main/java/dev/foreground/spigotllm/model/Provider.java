package dev.foreground.spigotllm.model;

public enum Provider {
    CODEX("codex"),
    CLAUDE("claude");

    private final String id;

    Provider(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Provider parse(String input) {
        if (input == null) {
            return null;
        }
        for (Provider provider : values()) {
            if (provider.id.equalsIgnoreCase(input)) {
                return provider;
            }
        }
        return null;
    }
}
