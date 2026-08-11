package dev.foreground.spigotllm.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable input used when registering a resource. */
public final class ResourceRegistration {
    private final String id;
    private final String owner;
    private final String promptScope;
    private final String type;
    private final Lifecycle lifecycle;
    private final Map<String, String> attributes;

    public ResourceRegistration(String id, String owner, String promptScope, String type,
                                Lifecycle lifecycle, Map<String, String> attributes) {
        this.id = id;
        this.owner = owner;
        this.promptScope = promptScope;
        this.type = type;
        this.lifecycle = lifecycle == null ? Lifecycle.prompt() : lifecycle;
        Map<String, String> copy = attributes == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(attributes);
        this.attributes = Collections.unmodifiableMap(copy);
    }

    public ResourceRegistration(String id, String owner, String promptScope, String type,
                                Lifecycle lifecycle) {
        this(id, owner, promptScope, type, lifecycle, null);
    }

    public String getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getPromptScope() {
        return promptScope;
    }

    public String getType() {
        return type;
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
