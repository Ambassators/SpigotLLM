package dev.foreground.spigotllm.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Persistent source workspace shared by authorized provider agents. */
public final class AgentWorkspace {
    private final Path root;

    private AgentWorkspace(Path root) {
        this.root = root;
    }

    public static AgentWorkspace open(Path pluginDataDirectory, String configured) throws IOException {
        Path data = pluginDataDirectory.toAbsolutePath().normalize();
        String value = configured == null ? "" : configured.trim();
        Path requested = Paths.get(value.isEmpty() ? "workspace" : value);

        // Earlier releases used "." as the default and resolved it against the server root.
        // Treat that legacy default like an unset value so upgrades gain a dedicated workspace.
        Path candidate;
        if (value.isEmpty() || ".".equals(value)) {
            candidate = data.resolve("workspace");
        } else {
            candidate = requested.isAbsolute() ? requested : data.resolve(requested);
        }

        Path normalized = candidate.toAbsolutePath().normalize();
        if (!requested.isAbsolute() && !normalized.startsWith(data)) {
            throw new IOException("Relative agent workspace must stay inside the plugin data directory: " + value);
        }
        Files.createDirectories(normalized);
        if (!Files.isDirectory(normalized)) {
            throw new IOException("Agent workspace is not a directory: " + normalized);
        }
        return new AgentWorkspace(normalized);
    }

    public Path root() {
        return root;
    }

    public String instructions() {
        return " Your persistent source workspace is " + root + ". Clone GitHub repositories into separate "
                + "child directories here and make source changes here. These repositories commonly contain "
                + "the source for Bukkit or Spigot plugins running on this Minecraft server; use the Minecraft "
                + "bridge to compare the source with live server state when useful.";
    }
}
