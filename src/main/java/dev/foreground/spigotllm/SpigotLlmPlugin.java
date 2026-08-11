package dev.foreground.spigotllm;

import dev.foreground.spigotllm.access.AccessStore;
import dev.foreground.spigotllm.account.LinkCodeCapture;
import dev.foreground.spigotllm.account.SecretStore;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.output.PrivateMessenger;
import dev.foreground.spigotllm.provider.ClaudeAdapter;
import dev.foreground.spigotllm.provider.CodexAdapter;
import dev.foreground.spigotllm.provider.ProviderAdapter;
import dev.foreground.spigotllm.runtime.RuntimeInstaller;
import dev.foreground.spigotllm.runtime.RuntimeResolver;
import dev.foreground.spigotllm.session.SessionStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

public final class SpigotLlmPlugin extends JavaPlugin {
    private PromptCoordinator coordinator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Path data = getDataFolder().toPath().toAbsolutePath().normalize();
            Files.createDirectories(data);

            AccessStore access = new AccessStore(data.resolve("authorized-operators.json"));
            access.load();
            SecretStore secrets = new SecretStore(data);
            secrets.initialize();
            SessionStore sessions = new SessionStore(data.resolve("identities"));
            RuntimeResolver resolver = new RuntimeResolver(data.resolve("runtimes"), getConfig());
            RuntimeInstaller installer = new RuntimeInstaller(
                    resolver,
                    getConfig().getInt("runtimes.connect-timeout-seconds", 20),
                    getConfig().getInt("runtimes.download-timeout-seconds", 900),
                    getConfig().getInt("runtimes.retain-releases", 2));

            Path agentWorkingDirectory = resolveWorkingDirectory(
                    getConfig().getString("execution.agent-working-directory", "."));
            int chatTimeout = getConfig().getInt("execution.chat-timeout-seconds", 300);
            int agentTimeout = getConfig().getInt("execution.agent-timeout-seconds", 1800);
            Map<Provider, ProviderAdapter> adapters = new EnumMap<Provider, ProviderAdapter>(Provider.class);
            adapters.put(Provider.CODEX, new CodexAdapter(
                    resolver, sessions, agentWorkingDirectory, chatTimeout, agentTimeout));
            adapters.put(Provider.CLAUDE, new ClaudeAdapter(
                    resolver, sessions, secrets, agentWorkingDirectory, chatTimeout, agentTimeout));

            PrivateMessenger messenger = new PrivateMessenger(
                    this,
                    getConfig().getInt("output.lines-per-page", 8),
                    getConfig().getInt("output.max-line-length", 220));
            coordinator = new PromptCoordinator(
                    this,
                    sessions,
                    messenger,
                    getConfig().getInt("execution.max-concurrent-prompts", 2),
                    adapters);
            LinkCodeCapture codeCapture = new LinkCodeCapture(access, coordinator, messenger);
            getServer().getPluginManager().registerEvents(codeCapture, this);

            SpigotLlmCommand commands = new SpigotLlmCommand(
                    access, sessions, installer, coordinator, messenger, codeCapture);
            register("spigotllm", commands);
            register("codex", commands);
            register("claude", commands);

            getLogger().info("Enabled. No player is trusted by default; authorize OP UUIDs from console with /sllm access add.");
        } catch (Exception e) {
            getLogger().severe("SpigotLLM could not initialize: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coordinator != null) coordinator.shutdown();
    }

    private void register(String name, SpigotLlmCommand commands) {
        PluginCommand command = getCommand(name);
        if (command == null) throw new IllegalStateException("Command missing from plugin.yml: " + name);
        command.setExecutor(commands);
        command.setTabCompleter(commands);
    }

    private Path resolveWorkingDirectory(String configured) throws IOException {
        File file = new File(configured == null || configured.trim().isEmpty() ? "." : configured.trim());
        Path path = file.toPath().toAbsolutePath().normalize();
        Files.createDirectories(path);
        if (!Files.isDirectory(path)) throw new IOException("Agent working directory is not a directory: " + path);
        return path;
    }
}
