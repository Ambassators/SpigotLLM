package dev.foreground.spigotllm;

import dev.foreground.spigotllm.access.AccessStore;
import dev.foreground.spigotllm.agent.AgentToolRuntime;
import dev.foreground.spigotllm.account.LinkCodeCapture;
import dev.foreground.spigotllm.account.SecretStore;
import dev.foreground.spigotllm.console.AgentConsoleBridge;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.output.PrivateMessenger;
import dev.foreground.spigotllm.provider.ClaudeAdapter;
import dev.foreground.spigotllm.provider.CodexAdapter;
import dev.foreground.spigotllm.provider.AgentWorkspace;
import dev.foreground.spigotllm.provider.ProviderAdapter;
import dev.foreground.spigotllm.runtime.RuntimeInstaller;
import dev.foreground.spigotllm.runtime.RuntimeResolver;
import dev.foreground.spigotllm.session.SessionStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

public final class SpigotLlmPlugin extends JavaPlugin {
    private PromptCoordinator coordinator;
    private AgentConsoleBridge consoleBridge;
    private AgentToolRuntime toolRuntime;

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

            AgentWorkspace agentWorkspace = AgentWorkspace.open(data,
                    getConfig().getString("execution.agent-working-directory", "workspace"));
            int chatTimeout = getConfig().getInt("execution.chat-timeout-seconds", 300);
            int agentTimeout = getConfig().getInt("execution.agent-timeout-seconds", 1800);
            Path consoleLog = resolveFilePath(getConfig().getString("agent-console.log-file", "logs/latest.log"));
            toolRuntime = new AgentToolRuntime(
                    this,
                    access,
                    data.resolve("agent-tools"),
                    consoleLog,
                    getConfig().getInt("agent-tools.max-resources-per-owner", 64),
                    getConfig().getInt("agent-tools.max-handles-per-owner", 512),
                    getConfig().getInt("agent-tools.max-source-bytes", 131072),
                    getConfig().getInt("agent-tools.max-queued-events", 5000),
                    getConfig().getLong("agent-tools.max-event-stream-bytes", 5242880L),
                    getConfig().getInt("agent-tools.compiler-threads", 1),
                    getConfig().getLong("agent-tools.slow-main-thread-millis", 50L),
                    getConfig().getBoolean("agent-tools.load-persistent", true),
                    getConfig().getBoolean("agent-tools.enabled", true),
                    getConfig().getLong("agent-tools.max-ttl-seconds", 86400L));
            consoleBridge = new AgentConsoleBridge(
                    this,
                    data.resolve("agent-console"),
                    consoleLog,
                    getConfig().getInt("agent-console.poll-ticks", 1),
                    getConfig().getInt("agent-console.max-command-length", 2048),
                    getConfig().getInt("agent-tools.max-request-bytes", 262144),
                    toolRuntime);
            Map<Provider, ProviderAdapter> adapters = new EnumMap<Provider, ProviderAdapter>(Provider.class);
            adapters.put(Provider.CODEX, new CodexAdapter(
                    resolver, sessions, consoleBridge, agentWorkspace, chatTimeout, agentTimeout));
            adapters.put(Provider.CLAUDE, new ClaudeAdapter(
                    resolver, sessions, secrets, consoleBridge,
                    agentWorkspace, chatTimeout, agentTimeout));

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
                    access, sessions, installer, coordinator, messenger, codeCapture, toolRuntime);
            register("spigotllm", commands);
            register("codex", commands);
            register("claude", commands);

            getLogger().info("Enabled. No player is trusted by default; authorize OP UUIDs from console with /sllm access add.");
            getLogger().info("Agent source workspace: " + agentWorkspace.root());
            getLogger().warning("Agent mode has access to the live server log and can dispatch commands as the real console.");
            if (getConfig().getBoolean("agent-tools.enabled", true)) {
                getLogger().warning("Agent runtime tools execute unsandboxed Java and deep reflection with full server/JVM authority.");
            }
        } catch (Exception e) {
            getLogger().severe("SpigotLLM could not initialize: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coordinator != null) coordinator.shutdown();
        if (consoleBridge != null) consoleBridge.shutdown();
        if (toolRuntime != null) toolRuntime.close();
    }

    private void register(String name, SpigotLlmCommand commands) {
        PluginCommand command = getCommand(name);
        if (command == null) throw new IllegalStateException("Command missing from plugin.yml: " + name);
        command.setExecutor(commands);
        command.setTabCompleter(commands);
    }

    private Path resolveFilePath(String configured) {
        java.io.File file = new java.io.File(configured == null || configured.trim().isEmpty()
                ? "logs/latest.log" : configured.trim());
        return file.toPath().toAbsolutePath().normalize();
    }
}
