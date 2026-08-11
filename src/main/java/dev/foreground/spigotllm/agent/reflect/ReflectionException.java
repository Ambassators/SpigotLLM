package dev.foreground.spigotllm.agent.reflect;

/**
 * A reflection request failure with a stable, machine-readable error code.
 */
public final class ReflectionException extends Exception {
    private final String code;

    public ReflectionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ReflectionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
