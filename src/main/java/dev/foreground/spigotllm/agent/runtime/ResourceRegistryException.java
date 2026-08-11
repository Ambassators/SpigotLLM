package dev.foreground.spigotllm.agent.runtime;

public final class ResourceRegistryException extends RuntimeException {
    private final String code;

    ResourceRegistryException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
