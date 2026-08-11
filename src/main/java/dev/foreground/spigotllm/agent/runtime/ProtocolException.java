package dev.foreground.spigotllm.agent.runtime;

/** Validation error safe to expose as a structured protocol response. */
public final class ProtocolException extends Exception {
    private final String code;
    private final String requestId;

    ProtocolException(String code, String message, String requestId) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }

    public String getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }
}
