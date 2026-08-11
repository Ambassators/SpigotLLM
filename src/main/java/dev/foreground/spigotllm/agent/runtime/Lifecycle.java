package dev.foreground.spigotllm.agent.runtime;

import java.util.Locale;
import java.util.Objects;

/** Describes how long an agent-created runtime resource may live. */
public final class Lifecycle {
    public static final long MAX_TTL_MILLIS = 24L * 60L * 60L * 1000L;

    public enum Type {
        PROMPT,
        TTL,
        REBOOT,
        PERSISTENT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Lifecycle PROMPT = new Lifecycle(Type.PROMPT, 0L);
    private static final Lifecycle REBOOT = new Lifecycle(Type.REBOOT, 0L);
    private static final Lifecycle PERSISTENT = new Lifecycle(Type.PERSISTENT, 0L);

    private final Type type;
    private final long ttlMillis;

    private Lifecycle(Type type, long ttlMillis) {
        this.type = type;
        this.ttlMillis = ttlMillis;
    }

    public static Lifecycle prompt() {
        return PROMPT;
    }

    public static Lifecycle reboot() {
        return REBOOT;
    }

    public static Lifecycle persistent() {
        return PERSISTENT;
    }

    public static Lifecycle ttlSeconds(long seconds) {
        if (seconds <= 0L || seconds > MAX_TTL_MILLIS / 1000L) {
            throw new IllegalArgumentException("TTL seconds must be between 1 and 86400.");
        }
        return new Lifecycle(Type.TTL, seconds * 1000L);
    }

    public static Lifecycle ttlMillis(long millis) {
        if (millis <= 0L || millis > MAX_TTL_MILLIS) {
            throw new IllegalArgumentException("TTL milliseconds must be between 1 and "
                    + MAX_TTL_MILLIS + ".");
        }
        return new Lifecycle(Type.TTL, millis);
    }

    public static Lifecycle named(String name) {
        if (name == null) throw new IllegalArgumentException("Lifecycle type is required.");
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if ("prompt".equals(normalized)) return prompt();
        if ("reboot".equals(normalized)) return reboot();
        if ("persistent".equals(normalized)) return persistent();
        if ("ttl".equals(normalized)) {
            throw new IllegalArgumentException("TTL lifecycle requires a duration.");
        }
        throw new IllegalArgumentException("Unknown lifecycle type: " + name);
    }

    public Type getType() {
        return type;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public Long expiresAt(long createdAtMillis) {
        if (type != Type.TTL) return null;
        if (Long.MAX_VALUE - createdAtMillis < ttlMillis) return Long.MAX_VALUE;
        return createdAtMillis + ttlMillis;
    }

    public boolean isExpired(long createdAtMillis, long nowMillis) {
        Long expiresAt = expiresAt(createdAtMillis);
        return expiresAt != null && nowMillis >= expiresAt.longValue();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Lifecycle)) return false;
        Lifecycle that = (Lifecycle) other;
        return type == that.type && ttlMillis == that.ttlMillis;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, ttlMillis);
    }

    @Override
    public String toString() {
        return type == Type.TTL ? "ttl(" + ttlMillis + "ms)" : type.id();
    }
}
