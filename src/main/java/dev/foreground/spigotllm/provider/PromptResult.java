package dev.foreground.spigotllm.provider;

public final class PromptResult {
    private final String response;
    private final String providerSessionId;

    public PromptResult(String response, String providerSessionId) {
        this.response = response;
        this.providerSessionId = providerSessionId;
    }

    public String response() {
        return response;
    }

    public String providerSessionId() {
        return providerSessionId;
    }
}
