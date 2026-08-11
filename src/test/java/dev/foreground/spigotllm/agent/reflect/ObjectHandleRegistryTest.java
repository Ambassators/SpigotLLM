package dev.foreground.spigotllm.agent.reflect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ObjectHandleRegistryTest {
    @Test
    void scopesHandlesToTheirOwnerAndReleasesThem() throws Exception {
        ObjectHandleRegistry registry = new ObjectHandleRegistry(2);
        Object value = new Object();
        String handle = registry.register("owner-a", value);

        assertSame(value, registry.resolve("owner-a", handle));
        ReflectionException error = assertThrows(ReflectionException.class,
                () -> registry.resolve("owner-b", handle));
        assertEquals("unknown_handle", error.getCode());
        assertTrue(registry.release("owner-a", handle));
        assertFalse(registry.release("owner-a", handle));
    }

    @Test
    void enforcesPerOwnerLimitAndCanReleaseAnOwner() throws Exception {
        ObjectHandleRegistry registry = new ObjectHandleRegistry(1);
        Object first = new Object();
        String handle = registry.register("owner", first);
        assertEquals(handle, registry.register("owner", first));

        ReflectionException error = assertThrows(ReflectionException.class,
                () -> registry.register("owner", new Object()));
        assertEquals("handle_limit", error.getCode());
        assertEquals(1, registry.releaseOwner("owner"));
        assertEquals(0, registry.size("owner"));
    }
}
