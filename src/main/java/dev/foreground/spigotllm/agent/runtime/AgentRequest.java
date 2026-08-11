package dev.foreground.spigotllm.agent.runtime;

import com.google.gson.JsonObject;

/** A validated request envelope from an agent lease. */
public final class AgentRequest {
    private final String id;
    private final String operation;
    private final JsonObject arguments;
    private final Lifecycle lifecycle;

    AgentRequest(String id, String operation, JsonObject arguments, Lifecycle lifecycle) {
        this.id = id;
        this.operation = operation;
        this.arguments = arguments.deepCopy();
        this.lifecycle = lifecycle;
    }

    public String getId() {
        return id;
    }

    public String getOperation() {
        return operation;
    }

    public JsonObject getArguments() {
        return arguments.deepCopy();
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }
}
