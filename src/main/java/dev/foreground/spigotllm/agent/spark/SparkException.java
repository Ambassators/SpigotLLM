package dev.foreground.spigotllm.agent.spark;

/** A stable, actionable error exposed through the agent protocol. */
public final class SparkException extends RuntimeException {
    private final String code;

    public SparkException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
