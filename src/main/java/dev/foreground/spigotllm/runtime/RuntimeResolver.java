package dev.foreground.spigotllm.runtime;

import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.util.JsonFiles;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeResolver {
    private final Path runtimesRoot;
    private final FileConfiguration config;

    public RuntimeResolver(Path runtimesRoot, FileConfiguration config) {
        this.runtimesRoot = runtimesRoot;
        this.config = config;
    }

    public Path resolve(Provider provider) throws IOException {
        String configured = config.getString("runtimes." + provider.id() + ".executable", "").trim();
        if (!configured.isEmpty()) {
            Path path = new File(configured).toPath().toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
            throw new IOException("Configured " + provider.id() + " executable does not exist: " + path);
        }
        Path metadataPath = metadataPath(provider);
        RuntimeMetadata metadata = JsonFiles.read(metadataPath, RuntimeMetadata.class, null);
        if (metadata == null || metadata.currentExecutable == null) {
            throw new IOException(provider.id() + " runtime is not installed. Run: sllm runtime install " + provider.id());
        }
        Path executable = runtimesRoot.resolve(provider.id()).resolve(metadata.currentExecutable).normalize();
        if (!executable.startsWith(runtimesRoot.resolve(provider.id()).normalize()) || !Files.isRegularFile(executable)) {
            throw new IOException("Installed " + provider.id() + " runtime is missing or invalid");
        }
        return executable;
    }

    public RuntimeMetadata metadata(Provider provider) throws IOException {
        return JsonFiles.read(metadataPath(provider), RuntimeMetadata.class, null);
    }

    public Path metadataPath(Provider provider) {
        return runtimesRoot.resolve(provider.id()).resolve("current.json");
    }

    public Path root(Provider provider) {
        return runtimesRoot.resolve(provider.id());
    }
}
