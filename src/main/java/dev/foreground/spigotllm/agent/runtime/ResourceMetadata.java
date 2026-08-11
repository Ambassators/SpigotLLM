package dev.foreground.spigotllm.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable snapshot of a resource's ownership, lifecycle, and current state. */
public final class ResourceMetadata {
    private final String id;
    private final String owner;
    private final String promptScope;
    private final String type;
    private final Lifecycle lifecycle;
    private final long createdAtMillis;
    private final Long expiresAtMillis;
    private final long updatedAtMillis;
    private final ResourceState state;
    private final String failure;
    private final Map<String, String> attributes;

    ResourceMetadata(String id, String owner, String promptScope, String type,
                     Lifecycle lifecycle, long createdAtMillis, Long expiresAtMillis,
                     long updatedAtMillis, ResourceState state, String failure,
                     Map<String, String> attributes) {
        this.id = id;
        this.owner = owner;
        this.promptScope = promptScope;
        this.type = type;
        this.lifecycle = lifecycle;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.updatedAtMillis = updatedAtMillis;
        this.state = state;
        this.failure = failure;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    public String getId() { return id; }
    public String getOwner() { return owner; }
    public String getPromptScope() { return promptScope; }
    public String getType() { return type; }
    public Lifecycle getLifecycle() { return lifecycle; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public Long getExpiresAtMillis() { return expiresAtMillis; }
    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public ResourceState getState() { return state; }
    public boolean isEnabled() { return state == ResourceState.ENABLED; }
    public String getFailure() { return failure; }
    public Map<String, String> getAttributes() { return attributes; }
}
