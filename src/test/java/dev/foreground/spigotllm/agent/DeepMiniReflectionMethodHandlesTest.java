package dev.foreground.spigotllm.agent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DeepMiniReflectionMethodHandlesTest {
    @Test
    void readsWhenAllowedOrPreciselyReportsBothBlockedAccessPaths() throws Exception {
        DeepMiniReflection reflection = new DeepMiniReflection(getClass().getClassLoader());
        try {
            Object value = reflection.get(new StringBuilder("text"), "value");
            // Java 8 generally permits the private inherited field.
            assertTrue(value != null && value.getClass().isArray() && Array.getLength(value) >= 4);
        } catch (IllegalAccessException denied) {
            // Module-aware JVMs normally deny both setAccessible and unreflect.
            assertTrue(denied.getMessage().contains("Reflection and MethodHandles"));
        }
    }
}
