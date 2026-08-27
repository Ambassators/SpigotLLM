package dev.foreground.spigotllm.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentWorkspaceTest {
    @TempDir Path temporary;

    @Test
    void createsPluginLocalDefaultAndMigratesLegacyDot() throws Exception {
        Path data = temporary.resolve("plugins/SpigotLLM");

        AgentWorkspace unset = AgentWorkspace.open(data, "");
        AgentWorkspace legacy = AgentWorkspace.open(data, ".");

        Path expected = data.resolve("workspace").toAbsolutePath().normalize();
        assertEquals(expected, unset.root());
        assertEquals(expected, legacy.root());
        assertTrue(Files.isDirectory(expected));
    }

    @Test
    void resolvesRelativeDirectoriesInsidePluginData() throws Exception {
        Path data = temporary.resolve("plugin-data");
        AgentWorkspace workspace = AgentWorkspace.open(data, "sources/github");

        assertEquals(data.resolve("sources/github").toAbsolutePath().normalize(), workspace.root());
        assertTrue(workspace.instructions().contains(workspace.root().toString()));
        assertTrue(workspace.instructions().contains("Clone GitHub repositories"));
    }

    @Test
    void permitsExplicitAbsoluteOverride() throws Exception {
        Path data = temporary.resolve("plugin-data");
        Path external = temporary.resolve("external-source").toAbsolutePath();

        assertEquals(external.normalize(), AgentWorkspace.open(data, external.toString()).root());
    }

    @Test
    void rejectsRelativeTraversalOutsidePluginData() {
        Path data = temporary.resolve("plugin-data");

        assertThrows(IOException.class, () -> AgentWorkspace.open(data, "../server-root"));
    }
}
