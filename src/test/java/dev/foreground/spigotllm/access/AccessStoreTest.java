package dev.foreground.spigotllm.access;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AccessStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsAnExplicitUuidAllowlist() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AccessStore store = new AccessStore(temporaryDirectory.resolve("access.json"));
        store.load();
        assertTrue(store.add(first, "FirstOp"));
        assertFalse(store.add(first, "RenamedOp"));
        assertTrue(store.add(second, "SecondOp"));

        AccessStore reloaded = new AccessStore(temporaryDirectory.resolve("access.json"));
        reloaded.load();
        assertTrue(reloaded.isAuthorized(first));
        assertTrue(reloaded.isAuthorized(second));
        assertEquals("RenamedOp", reloaded.list().get(0).getLastKnownName());
        assertTrue(reloaded.remove(first));
        assertFalse(reloaded.isAuthorized(first));
    }
}
