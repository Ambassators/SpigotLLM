package dev.foreground.spigotllm.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReasoningEffortTest {
    @Test
    void parsesDefaultsAndRejectsUnknownValues() {
        assertEquals(ReasoningEffort.DEFAULT, ReasoningEffort.parse("default"));
        assertEquals(ReasoningEffort.DEFAULT, ReasoningEffort.parse("auto"));
        assertEquals(ReasoningEffort.XHIGH, ReasoningEffort.parse("XHIGH"));
        assertNull(ReasoningEffort.parse("extreme"));
    }

    @Test
    void enforcesProviderSpecificLevels() {
        assertTrue(ReasoningEffort.MINIMAL.supports(Provider.CODEX));
        assertTrue(ReasoningEffort.XHIGH.supports(Provider.CODEX));
        assertFalse(ReasoningEffort.MAX.supports(Provider.CODEX));
        assertFalse(ReasoningEffort.MINIMAL.supports(Provider.CLAUDE));
        assertTrue(ReasoningEffort.MAX.supports(Provider.CLAUDE));
        assertTrue(ReasoningEffort.ULTRACODE.supports(Provider.CLAUDE));
    }
}
