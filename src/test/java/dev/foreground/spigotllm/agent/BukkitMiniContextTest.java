package dev.foreground.spigotllm.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.foreground.spigotllm.agent.code.MiniCommandAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BukkitMiniContextTest {
    @Test
    void emitsStructuredValuesAndDelegatesOpaqueHandleOperations() {
        RecordingHandles handles = new RecordingHandles();
        List<JsonObject> events = new ArrayList<JsonObject>();
        BukkitMiniContext context = new BukkitMiniContext(null, "owner", "module-1",
                null, null, handles, event -> events.add(event.deepCopy()), null, 50L, 4);
        Object value = new Object();

        assertSame(value, context.emit(value));
        assertEquals(1, events.size());
        assertEquals("code.emit", events.get(0).get("type").getAsString());
        assertEquals("module-1", events.get(0).get("resourceId").getAsString());
        assertEquals("encoded", events.get(0).get("value").getAsString());
        assertEquals("owner", context.owner());
        assertEquals("module-1", context.resourceId());

        assertEquals("handle-1", context.retain(value));
        assertSame(value, context.resolve("handle-1"));
        assertTrue(context.release("handle-1"));
        assertFalse(context.release("missing"));
    }

    @Test
    void closedContextRejectsAllNewTrackedChildrenAndValidatesMessageHelpers() {
        BukkitMiniContext context = new BukkitMiniContext(null, "owner", "module-1",
                null, null, new RecordingHandles(), event -> { }, null, 50L, 4);
        context.close();
        context.close();

        assertThrows(IllegalStateException.class, () -> context.runSync(null));
        assertThrows(IllegalStateException.class, () -> context.runLater(null, 1L));
        assertThrows(IllegalStateException.class, () -> context.runTimer(null, 1L, 1L));
        assertThrows(IllegalStateException.class, () -> context.listen(null, null, false, null));
        assertThrows(IllegalStateException.class, () -> context.command("test", Collections.<String>emptyList(),
                "/test", MiniCommandAccess.EVERYONE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> context.dispatchCommand(null, " /  "));
        assertThrows(IllegalArgumentException.class, () -> context.sendMessage(null, "message"));
    }

    private static final class RecordingHandles implements BukkitMiniContext.HandleAccess {
        private Object value;
        private boolean released;

        @Override public String retain(Object value) {
            this.value = value;
            released = false;
            return "handle-1";
        }
        @Override public Object resolve(String handle) {
            return !released && "handle-1".equals(handle) ? value : null;
        }
        @Override public boolean release(String handle) {
            if (released || !"handle-1".equals(handle)) return false;
            released = true;
            return true;
        }
        @Override public JsonElement encode(Object value) {
            return new JsonPrimitive("encoded");
        }
    }
}
