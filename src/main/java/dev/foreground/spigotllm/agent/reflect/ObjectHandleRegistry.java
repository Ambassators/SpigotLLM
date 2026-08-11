package dev.foreground.spigotllm.agent.reflect;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Owner-scoped registry for opaque, non-persistent live-object handles. */
public final class ObjectHandleRegistry {
    private final int maximumPerOwner;
    private final Map<String, OwnerHandles> handles = new HashMap<String, OwnerHandles>();

    public ObjectHandleRegistry(int maximumPerOwner) {
        if (maximumPerOwner < 1) throw new IllegalArgumentException("maximumPerOwner must be positive");
        this.maximumPerOwner = maximumPerOwner;
    }

    public synchronized String register(String owner, Object value) throws ReflectionException {
        requireOwner(owner);
        if (value == null) throw new ReflectionException("invalid_handle_value", "Null values do not need handles.");
        OwnerHandles ownerHandles = handles.get(owner);
        if (ownerHandles == null) {
            ownerHandles = new OwnerHandles();
            handles.put(owner, ownerHandles);
        }
        String existing = ownerHandles.byIdentity.get(value);
        if (existing != null) return existing;
        if (ownerHandles.byId.size() >= maximumPerOwner) {
            throw new ReflectionException("handle_limit", "The owner has reached the live-object handle limit.");
        }
        String handle;
        do {
            handle = "h_" + UUID.randomUUID().toString().replace("-", "");
        } while (ownerHandles.byId.containsKey(handle));
        ownerHandles.byId.put(handle, value);
        ownerHandles.byIdentity.put(value, handle);
        return handle;
    }

    public synchronized Object resolve(String owner, String handle) throws ReflectionException {
        requireOwner(owner);
        if (handle == null || handle.isEmpty()) {
            throw new ReflectionException("invalid_handle", "A handle is required.");
        }
        OwnerHandles ownerHandles = handles.get(owner);
        Object value = ownerHandles == null ? null : ownerHandles.byId.get(handle);
        if (value == null) {
            throw new ReflectionException("unknown_handle", "The handle does not exist for this owner.");
        }
        return value;
    }

    public synchronized boolean release(String owner, String handle) throws ReflectionException {
        requireOwner(owner);
        OwnerHandles ownerHandles = handles.get(owner);
        if (ownerHandles == null) return false;
        Object removed = ownerHandles.byId.remove(handle);
        if (removed == null) return false;
        if (handle.equals(ownerHandles.byIdentity.get(removed))) ownerHandles.byIdentity.remove(removed);
        if (ownerHandles.byId.isEmpty()) handles.remove(owner);
        return true;
    }

    public synchronized int releaseOwner(String owner) throws ReflectionException {
        requireOwner(owner);
        OwnerHandles removed = handles.remove(owner);
        return removed == null ? 0 : removed.byId.size();
    }

    public synchronized int size(String owner) throws ReflectionException {
        requireOwner(owner);
        OwnerHandles ownerHandles = handles.get(owner);
        return ownerHandles == null ? 0 : ownerHandles.byId.size();
    }

    public int getMaximumPerOwner() {
        return maximumPerOwner;
    }

    private static void requireOwner(String owner) throws ReflectionException {
        if (owner == null || owner.trim().isEmpty()) {
            throw new ReflectionException("invalid_owner", "A non-empty owner is required.");
        }
    }

    private static final class OwnerHandles {
        private final Map<String, Object> byId = new HashMap<String, Object>();
        private final IdentityHashMap<Object, String> byIdentity = new IdentityHashMap<Object, String>();
    }
}
