package dev.foreground.spigotllm.access;

public final class AuthorizedOperator {
    private String uuid;
    private String lastKnownName;
    private long addedAt;

    public AuthorizedOperator() {
    }

    public AuthorizedOperator(String uuid, String lastKnownName, long addedAt) {
        this.uuid = uuid;
        this.lastKnownName = lastKnownName;
        this.addedAt = addedAt;
    }

    public String getUuid() {
        return uuid;
    }

    public String getLastKnownName() {
        return lastKnownName;
    }

    public long getAddedAt() {
        return addedAt;
    }
}
