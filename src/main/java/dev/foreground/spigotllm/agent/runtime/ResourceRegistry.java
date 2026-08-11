package dev.foreground.spigotllm.agent.runtime;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Thread-safe in-memory ownership and lifecycle registry. It intentionally has
 * no scheduler dependency; callers should invoke {@link #cleanupExpired()} on
 * their existing bridge polling task.
 */
public final class ResourceRegistry {
    public static final int DEFAULT_PER_OWNER_LIMIT = 64;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,127}");

    private final int perOwnerLimit;
    private final Clock clock;
    private final Map<Key, Entry> resources = new LinkedHashMap<Key, Entry>();

    public ResourceRegistry() {
        this(DEFAULT_PER_OWNER_LIMIT);
    }

    public ResourceRegistry(int perOwnerLimit) {
        this(perOwnerLimit, Clock.systemUTC());
    }

    ResourceRegistry(int perOwnerLimit, Clock clock) {
        if (perOwnerLimit < 1) throw new IllegalArgumentException("Per-owner limit must be positive.");
        if (clock == null) throw new IllegalArgumentException("Clock is required.");
        this.perOwnerLimit = perOwnerLimit;
        this.clock = clock;
    }

    public synchronized ResourceMetadata register(ResourceRegistration registration,
                                                  ManagedResource resource) {
        return register(registration, resource, true);
    }

    public synchronized ResourceMetadata registerDisabled(ResourceRegistration registration,
                                                          ManagedResource resource) {
        return register(registration, resource, false);
    }

    private ResourceMetadata register(ResourceRegistration registration, ManagedResource resource,
                                      boolean enableImmediately) {
        validate(registration, resource);
        cleanupExpiredLocked();
        Key key = new Key(registration.getOwner(), registration.getId());
        if (resources.containsKey(key)) {
            throw problem("resource_exists", "Resource already exists for this owner: "
                    + registration.getId());
        }
        if (countOwner(registration.getOwner()) >= perOwnerLimit) {
            throw problem("owner_limit", "Owner has reached the resource limit of " + perOwnerLimit + ".");
        }

        long now = clock.millis();
        Entry entry = new Entry(registration, resource, now);
        resources.put(key, entry);
        if (enableImmediately) enableEntry(entry);
        return entry.snapshot();
    }

    public synchronized List<ResourceMetadata> list(String owner) {
        requireText(owner, "Owner", 256);
        cleanupExpiredLocked();
        List<ResourceMetadata> result = new ArrayList<ResourceMetadata>();
        for (Entry entry : resources.values()) {
            if (owner.equals(entry.owner)) result.add(entry.snapshot());
        }
        sort(result);
        return Collections.unmodifiableList(result);
    }

    public synchronized List<ResourceMetadata> listAll() {
        cleanupExpiredLocked();
        List<ResourceMetadata> result = new ArrayList<ResourceMetadata>();
        for (Entry entry : resources.values()) result.add(entry.snapshot());
        sort(result);
        return Collections.unmodifiableList(result);
    }

    public synchronized ResourceMetadata inspect(String owner, String id) {
        cleanupExpiredLocked();
        return required(owner, id).snapshot();
    }

    public synchronized ResourceMetadata enable(String owner, String id) {
        cleanupExpiredLocked();
        Entry entry = required(owner, id);
        if (entry.state != ResourceState.ENABLED) enableEntry(entry);
        return entry.snapshot();
    }

    public synchronized ResourceMetadata disable(String owner, String id) {
        cleanupExpiredLocked();
        Entry entry = required(owner, id);
        if (entry.state == ResourceState.DISABLED) return entry.snapshot();
        try {
            entry.resource.disable();
            entry.state = ResourceState.DISABLED;
            entry.failure = null;
        } catch (Exception failure) {
            fail(entry, failure);
        }
        entry.updatedAtMillis = clock.millis();
        return entry.snapshot();
    }

    /** Resets a TTL resource's expiry relative to now, up to the global 24-hour cap. */
    public synchronized ResourceMetadata extend(String owner, String id, long ttlSeconds) {
        cleanupExpiredLocked();
        Entry entry = required(owner, id);
        if (entry.lifecycle.getType() != Lifecycle.Type.TTL) {
            throw problem("not_ttl", "Only TTL resources can be extended.");
        }
        Lifecycle extension;
        try { extension = Lifecycle.ttlSeconds(ttlSeconds); }
        catch (IllegalArgumentException e) { throw problem("invalid_ttl", e.getMessage()); }
        entry.expiresAtMillis = extension.expiresAt(clock.millis());
        entry.updatedAtMillis = clock.millis();
        return entry.snapshot();
    }

    /** Records an asynchronous callback failure without discarding its resource. */
    public synchronized ResourceMetadata markFailed(String owner, String id, Throwable failure) {
        cleanupExpiredLocked();
        Entry entry = required(owner, id);
        fail(entry, failure);
        entry.updatedAtMillis = clock.millis();
        return entry.snapshot();
    }

    public synchronized ResourceMetadata remove(String owner, String id) {
        cleanupExpiredLocked();
        Entry entry = required(owner, id);
        resources.remove(new Key(owner, id));
        String failure = closeEntry(entry, RemovalReason.EXPLICIT);
        if (failure != null) {
            throw problem("cleanup_failed", "Resource was removed, but cleanup failed: " + failure);
        }
        return entry.snapshot();
    }

    public synchronized CleanupReport closePromptScope(String owner, String promptScope) {
        requireText(owner, "Owner", 256);
        requireText(promptScope, "Prompt scope", 256);
        return removeMatching(owner, promptScope, Lifecycle.Type.PROMPT, RemovalReason.PROMPT_CLOSED);
    }

    public synchronized CleanupReport removeOwner(String owner) {
        requireText(owner, "Owner", 256);
        return removeMatching(owner, null, null, RemovalReason.OWNER_PURGED);
    }

    public synchronized CleanupReport cleanupExpired() {
        return cleanupExpiredLocked();
    }

    /** Detaches all resources, including persistent ones, from this live registry. */
    public synchronized CleanupReport shutdownClear() {
        return removeMatching(null, null, null, RemovalReason.SHUTDOWN);
    }

    public int getPerOwnerLimit() {
        return perOwnerLimit;
    }

    private void enableEntry(Entry entry) {
        try {
            entry.resource.enable();
            entry.state = ResourceState.ENABLED;
            entry.failure = null;
        } catch (Exception failure) {
            fail(entry, failure);
        }
        entry.updatedAtMillis = clock.millis();
    }

    private CleanupReport cleanupExpiredLocked() {
        long now = clock.millis();
        int removed = 0;
        Map<String, String> failures = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<Key, Entry>> iterator = resources.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.expiresAtMillis == null || now < entry.expiresAtMillis.longValue()) continue;
            iterator.remove();
            removed++;
            String failure = closeEntry(entry, RemovalReason.EXPIRED);
            if (failure != null) failures.put(entry.owner + "/" + entry.id, failure);
        }
        return new CleanupReport(removed, failures);
    }

    private CleanupReport removeMatching(String owner, String promptScope, Lifecycle.Type lifecycle,
                                         RemovalReason reason) {
        int removed = 0;
        Map<String, String> failures = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<Key, Entry>> iterator = resources.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (owner != null && !owner.equals(entry.owner)) continue;
            if (promptScope != null && !promptScope.equals(entry.promptScope)) continue;
            if (lifecycle != null && lifecycle != entry.lifecycle.getType()) continue;
            iterator.remove();
            removed++;
            String failure = closeEntry(entry, reason);
            if (failure != null) failures.put(entry.owner + "/" + entry.id, failure);
        }
        return new CleanupReport(removed, failures);
    }

    private String closeEntry(Entry entry, RemovalReason reason) {
        List<String> failures = new ArrayList<String>();
        if (entry.state != ResourceState.DISABLED) {
            try {
                entry.resource.disable();
            } catch (Exception failure) {
                failures.add(safeFailure(failure));
            }
        }
        try {
            entry.resource.close(reason);
        } catch (Exception failure) {
            failures.add(safeFailure(failure));
        }
        entry.state = ResourceState.DISABLED;
        entry.updatedAtMillis = clock.millis();
        if (failures.isEmpty()) {
            entry.failure = null;
            return null;
        }
        String combined = join(failures);
        entry.failure = combined;
        return combined;
    }

    private Entry required(String owner, String id) {
        requireText(owner, "Owner", 256);
        if (id == null || !ID.matcher(id).matches()) {
            throw problem("invalid_id", "Invalid resource id.");
        }
        Entry entry = resources.get(new Key(owner, id));
        if (entry == null) throw problem("not_found", "Resource was not found for this owner: " + id);
        return entry;
    }

    private int countOwner(String owner) {
        int count = 0;
        for (Entry entry : resources.values()) if (owner.equals(entry.owner)) count++;
        return count;
    }

    private static void validate(ResourceRegistration registration, ManagedResource resource) {
        if (registration == null) throw problem("invalid_resource", "Registration is required.");
        if (resource == null) throw problem("invalid_resource", "Managed resource is required.");
        if (registration.getId() == null || !ID.matcher(registration.getId()).matches()) {
            throw problem("invalid_id", "Resource id must be 1-128 filesystem-safe characters.");
        }
        requireText(registration.getOwner(), "Owner", 256);
        requireText(registration.getPromptScope(), "Prompt scope", 256);
        if (registration.getType() == null || !TYPE.matcher(registration.getType()).matches()) {
            throw problem("invalid_type", "Invalid resource type.");
        }
        for (Map.Entry<String, String> attribute : registration.getAttributes().entrySet()) {
            requireText(attribute.getKey(), "Attribute name", 128);
            if (attribute.getValue() == null || attribute.getValue().length() > 4096) {
                throw problem("invalid_attribute", "Attribute values must be at most 4096 characters.");
            }
        }
    }

    private static void requireText(String value, String label, int maximumLength) {
        if (value == null || value.trim().isEmpty() || value.length() > maximumLength) {
            throw problem("invalid_value", label + " must be between 1 and " + maximumLength + " characters.");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw problem("invalid_value", label + " contains control characters.");
            }
        }
    }

    private static void fail(Entry entry, Throwable failure) {
        entry.state = ResourceState.FAILED;
        entry.failure = safeFailure(failure);
    }

    private static String safeFailure(Throwable failure) {
        if (failure == null) return "Unknown failure";
        String message = failure.getMessage();
        String value = failure.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
        value = value.replace('\r', ' ').replace('\n', ' ');
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append("; ");
            result.append(value);
        }
        return result.toString();
    }

    private static ResourceRegistryException problem(String code, String message) {
        return new ResourceRegistryException(code, message);
    }

    private static void sort(List<ResourceMetadata> resources) {
        Collections.sort(resources, new Comparator<ResourceMetadata>() {
            @Override public int compare(ResourceMetadata left, ResourceMetadata right) {
                int owner = left.getOwner().compareTo(right.getOwner());
                if (owner != 0) return owner;
                int created = Long.compare(left.getCreatedAtMillis(), right.getCreatedAtMillis());
                return created != 0 ? created : left.getId().compareTo(right.getId());
            }
        });
    }

    private final class Entry {
        private final String id;
        private final String owner;
        private final String promptScope;
        private final String type;
        private final Lifecycle lifecycle;
        private final long createdAtMillis;
        private Long expiresAtMillis;
        private final Map<String, String> attributes;
        private final ManagedResource resource;
        private long updatedAtMillis;
        private ResourceState state = ResourceState.DISABLED;
        private String failure;

        private Entry(ResourceRegistration registration, ManagedResource resource, long now) {
            this.id = registration.getId();
            this.owner = registration.getOwner();
            this.promptScope = registration.getPromptScope();
            this.type = registration.getType();
            this.lifecycle = registration.getLifecycle();
            this.createdAtMillis = now;
            this.expiresAtMillis = lifecycle.expiresAt(now);
            this.updatedAtMillis = now;
            this.attributes = registration.getAttributes();
            this.resource = resource;
        }

        private ResourceMetadata snapshot() {
            return new ResourceMetadata(id, owner, promptScope, type, lifecycle, createdAtMillis,
                    expiresAtMillis, updatedAtMillis, state, failure, attributes);
        }
    }

    private static final class Key {
        private final String owner;
        private final String id;

        private Key(String owner, String id) {
            this.owner = owner;
            this.id = id;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return owner.equals(that.owner) && id.equals(that.id);
        }

        @Override public int hashCode() {
            return 31 * owner.hashCode() + id.hashCode();
        }
    }
}
