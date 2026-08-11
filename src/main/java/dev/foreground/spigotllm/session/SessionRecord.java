package dev.foreground.spigotllm.session;

import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.ReasoningEffort;
import dev.foreground.spigotllm.model.SessionMode;

public final class SessionRecord {
    private String name;
    private Provider provider;
    private SessionMode mode;
    private String providerSessionId;
    private String reasoningEffort;
    private String storageId;
    private long createdAt;
    private long updatedAt;

    public SessionRecord() {
    }

    public SessionRecord(String name, Provider provider, SessionMode mode) {
        this.name = name;
        this.provider = provider;
        this.mode = mode;
        this.storageId = java.util.UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Provider getProvider() {
        return provider;
    }

    public SessionMode getMode() {
        return mode;
    }

    public String getProviderSessionId() {
        return providerSessionId;
    }

    public void setProviderSessionId(String providerSessionId) {
        this.providerSessionId = providerSessionId;
    }

    public ReasoningEffort getReasoningEffort() {
        ReasoningEffort parsed = ReasoningEffort.parse(reasoningEffort);
        return parsed == null ? ReasoningEffort.DEFAULT : parsed;
    }

    public void setReasoningEffort(ReasoningEffort effort) {
        this.reasoningEffort = effort == null || effort.isDefault() ? null : effort.id();
    }

    public String getStorageId() {
        ensureStorageId();
        return storageId;
    }

    boolean ensureStorageId() {
        if (storageId != null && storageId.matches("[0-9a-fA-F-]{36}")) return false;
        storageId = java.util.UUID.randomUUID().toString();
        return true;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public String key() {
        return provider.id() + ":" + mode.id() + ":" + name.toLowerCase(java.util.Locale.ROOT);
    }
}
