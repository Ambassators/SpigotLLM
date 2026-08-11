package dev.foreground.spigotllm.console;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AgentConsoleBridgeTest {
    @Test
    void parsesCompleteSingleLineRequests() {
        AgentConsoleBridge.ParsedRequest request = AgentConsoleBridge.parseRequest("request-1\t/list players\n", 100);
        assertEquals("request-1", request.id);
        assertEquals("list players", request.command);
    }

    @Test
    void waitsForFinalNewlineAndRejectsUnsafeShapes() {
        assertNull(AgentConsoleBridge.parseRequest("request-1\tlist", 100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConsoleBridge.parseRequest("request-1\tlist\nsecond\n", 100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConsoleBridge.parseRequest("bad id\tlist\n", 100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConsoleBridge.parseRequest("request-1\t123456\n", 5));
    }
}
