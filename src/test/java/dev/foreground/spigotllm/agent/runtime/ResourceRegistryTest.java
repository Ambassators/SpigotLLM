package dev.foreground.spigotllm.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceRegistryTest {
    @Test
    void tracksOwnershipStateAndPerOwnerLimit() {
        ResourceRegistry registry = new ResourceRegistry(1);
        RecordingResource first = new RecordingResource();
        ResourceMetadata created = registry.register(registration(
                "one", "owner-a", "prompt-1", Lifecycle.prompt()), first);

        assertTrue(created.isEnabled());
        assertEquals(1, first.enableCalls);
        assertEquals("event-watch", created.getType());
        assertThrows(UnsupportedOperationException.class,
                () -> created.getAttributes().put("new", "value"));

        ResourceRegistryException atLimit = assertThrows(ResourceRegistryException.class,
                () -> registry.register(registration(
                        "two", "owner-a", "prompt-1", Lifecycle.reboot()), new RecordingResource()));
        assertEquals("owner_limit", atLimit.getCode());

        // Resource ids are owner-scoped rather than global.
        registry.register(registration("one", "owner-b", "prompt-2", Lifecycle.reboot()),
                new RecordingResource());
        assertEquals(1, registry.list("owner-a").size());
        assertEquals(2, registry.listAll().size());
        assertThrows(ResourceRegistryException.class, () -> registry.inspect("owner-c", "one"));
    }

    @Test
    void preservesFailuresAndAllowsRetry() {
        ResourceRegistry registry = new ResourceRegistry();
        RecordingResource resource = new RecordingResource();
        resource.failEnable = true;
        ResourceMetadata failed = registry.register(registration(
                "module", "owner", "prompt", Lifecycle.reboot()), resource);
        assertEquals(ResourceState.FAILED, failed.getState());
        assertTrue(failed.getFailure().contains("enable failed"));

        resource.failEnable = false;
        ResourceMetadata enabled = registry.enable("owner", "module");
        assertEquals(ResourceState.ENABLED, enabled.getState());
        assertNull(enabled.getFailure());

        resource.failDisable = true;
        ResourceMetadata disableFailure = registry.disable("owner", "module");
        assertEquals(ResourceState.FAILED, disableFailure.getState());
        resource.failDisable = false;
        assertEquals(ResourceState.DISABLED, registry.disable("owner", "module").getState());

        assertEquals(ResourceState.FAILED,
                registry.markFailed("owner", "module", new IllegalStateException("callback failed")).getState());
    }

    @Test
    void closesOnlyMatchingPromptResources() {
        ResourceRegistry registry = new ResourceRegistry();
        RecordingResource matching = new RecordingResource();
        RecordingResource anotherPrompt = new RecordingResource();
        RecordingResource reboot = new RecordingResource();
        registry.register(registration("a", "owner", "prompt-a", Lifecycle.prompt()), matching);
        registry.register(registration("b", "owner", "prompt-b", Lifecycle.prompt()), anotherPrompt);
        registry.register(registration("c", "owner", "prompt-a", Lifecycle.reboot()), reboot);

        CleanupReport report = registry.closePromptScope("owner", "prompt-a");
        assertEquals(1, report.getRemoved());
        assertTrue(report.isSuccessful());
        assertEquals(RemovalReason.PROMPT_CLOSED, matching.reason);
        assertEquals(2, registry.list("owner").size());
        assertNull(anotherPrompt.reason);
        assertNull(reboot.reason);
    }

    @Test
    void expiresTtlAndContinuesAfterCleanupFailure() {
        MutableClock clock = new MutableClock(1_000L);
        ResourceRegistry registry = new ResourceRegistry(2, clock);
        RecordingResource healthy = new RecordingResource();
        RecordingResource broken = new RecordingResource();
        broken.failClose = true;
        registry.register(registration("healthy", "owner", "p", Lifecycle.ttlMillis(10)), healthy);
        registry.register(registration("broken", "owner", "p", Lifecycle.ttlMillis(20)), broken);

        clock.advance(15L);
        CleanupReport first = registry.cleanupExpired();
        assertEquals(1, first.getRemoved());
        assertEquals(RemovalReason.EXPIRED, healthy.reason);
        assertEquals(1, registry.list("owner").size());

        clock.advance(10L);
        CleanupReport second = registry.cleanupExpired();
        assertEquals(1, second.getRemoved());
        assertFalse(second.isSuccessful());
        assertTrue(second.getFailures().containsKey("owner/broken"));
        assertTrue(registry.list("owner").isEmpty());
    }

    @Test
    void removeAndShutdownRunFullCleanup() {
        ResourceRegistry registry = new ResourceRegistry();
        RecordingResource explicit = new RecordingResource();
        RecordingResource persistent = new RecordingResource();
        registry.register(registration("explicit", "owner", "p", Lifecycle.reboot()), explicit);
        registry.registerDisabled(registration(
                "persistent", "owner", "p", Lifecycle.persistent()), persistent);

        ResourceMetadata removed = registry.remove("owner", "explicit");
        assertEquals(ResourceState.DISABLED, removed.getState());
        assertEquals(1, explicit.disableCalls);
        assertEquals(RemovalReason.EXPLICIT, explicit.reason);

        CleanupReport shutdown = registry.shutdownClear();
        assertEquals(1, shutdown.getRemoved());
        assertEquals(0, persistent.disableCalls);
        assertEquals(RemovalReason.SHUTDOWN, persistent.reason);
        assertTrue(registry.listAll().isEmpty());
    }

    @Test
    void lifecycleEnforcesMaximumTtl() {
        assertEquals(Lifecycle.Type.PROMPT, Lifecycle.prompt().getType());
        assertEquals(Long.valueOf(86_400_000L), Lifecycle.ttlSeconds(86_400).expiresAt(0L));
        assertThrows(IllegalArgumentException.class, () -> Lifecycle.ttlSeconds(86_401));
        assertThrows(IllegalArgumentException.class, () -> Lifecycle.ttlMillis(0));
    }

    @Test
    void extendsOnlyTtlResourcesFromCurrentTime() {
        MutableClock clock = new MutableClock(1_000L);
        ResourceRegistry registry = new ResourceRegistry(2, clock);
        registry.register(registration("ttl", "owner", "p", Lifecycle.ttlSeconds(5)), new RecordingResource());
        registry.register(registration("reboot", "owner", "p", Lifecycle.reboot()), new RecordingResource());

        clock.advance(2_000L);
        ResourceMetadata extended = registry.extend("owner", "ttl", 10L);
        assertEquals(Long.valueOf(13_000L), extended.getExpiresAtMillis());
        ResourceRegistryException failure = assertThrows(ResourceRegistryException.class,
                () -> registry.extend("owner", "reboot", 10L));
        assertEquals("not_ttl", failure.getCode());
    }

    private static ResourceRegistration registration(String id, String owner, String prompt,
                                                     Lifecycle lifecycle) {
        return new ResourceRegistration(id, owner, prompt, "event-watch", lifecycle,
                Collections.singletonMap("sourceHash", "abc123"));
    }

    private static final class RecordingResource implements ManagedResource {
        private int enableCalls;
        private int disableCalls;
        private int closeCalls;
        private boolean failEnable;
        private boolean failDisable;
        private boolean failClose;
        private RemovalReason reason;

        @Override public void enable() {
            enableCalls++;
            if (failEnable) throw new IllegalStateException("enable failed");
        }

        @Override public void disable() {
            disableCalls++;
            if (failDisable) throw new IllegalStateException("disable failed");
        }

        @Override public void close(RemovalReason reason) {
            closeCalls++;
            this.reason = reason;
            if (failClose) throw new IllegalStateException("close failed");
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        private void advance(long amount) {
            millis += amount;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override public long millis() {
            return millis;
        }
    }
}
