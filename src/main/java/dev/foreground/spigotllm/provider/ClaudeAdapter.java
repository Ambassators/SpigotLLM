package dev.foreground.spigotllm.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foreground.spigotllm.account.SecretStore;
import dev.foreground.spigotllm.console.AgentConsoleBridge;
import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.ReasoningEffort;
import dev.foreground.spigotllm.model.SessionMode;
import dev.foreground.spigotllm.runtime.RuntimeResolver;
import dev.foreground.spigotllm.session.SessionRecord;
import dev.foreground.spigotllm.session.SessionStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClaudeAdapter implements ProviderAdapter {
    private static final Pattern OAUTH_TOKEN = Pattern.compile("(sk-ant-[A-Za-z0-9_-]{20,})");

    private final RuntimeResolver runtimes;
    private final SessionStore sessions;
    private final SecretStore secrets;
    private final AgentConsoleBridge consoleBridge;
    private final Path agentWorkingDirectory;
    private final int chatTimeout;
    private final int agentTimeout;
    private final ProcessSupport processes = new ProcessSupport();
    private final ActiveProcesses active = new ActiveProcesses();
    private final Map<String, ProcessSupport.Running> pendingLinks = new HashMap<String, ProcessSupport.Running>();
    private final ScheduledExecutorService linkTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SpigotLLM-claude-link-timeouts");
        thread.setDaemon(true);
        return thread;
    });

    public ClaudeAdapter(RuntimeResolver runtimes, SessionStore sessions, SecretStore secrets,
                         AgentConsoleBridge consoleBridge, Path agentWorkingDirectory,
                         int chatTimeout, int agentTimeout) {
        this.runtimes = runtimes;
        this.sessions = sessions;
        this.secrets = secrets;
        this.consoleBridge = consoleBridge;
        this.agentWorkingDirectory = agentWorkingDirectory;
        this.chatTimeout = chatTimeout;
        this.agentTimeout = agentTimeout;
    }

    @Override
    public PromptResult prompt(final Identity identity, SessionRecord session, String prompt,
                               final ProgressListener progress) throws ProviderException {
        AgentConsoleBridge.Lease consoleLease = null;
        try {
            String token = secrets.get(identity, Provider.CLAUDE);
            if (token == null) throw new ProviderException("Claude account is not connected. Run /sllm account connect claude");
            Path executable = runtimes.resolve(Provider.CLAUDE);
            Path sessionRoot = sessions.claudeSessionRoot(identity, session);
            Path configRoot = sessionRoot.resolve("config");
            Path cwd = session.getMode() == SessionMode.AGENT
                    ? agentWorkingDirectory
                    : sessionRoot.resolve("workspace");
            Files.createDirectories(configRoot);
            Files.createDirectories(cwd);

            boolean resume = session.getProviderSessionId() != null && !session.getProviderSessionId().isEmpty();
            String sessionId = resume ? session.getProviderSessionId() : UUID.randomUUID().toString();
            List<String> command = new ArrayList<String>();
            command.add(executable.toString());
            command.add("-p");
            command.add("--input-format");
            command.add("text");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--verbose");
            ReasoningEffort effort = session.getReasoningEffort();
            if (!effort.isDefault()) {
                if (!effort.supports(Provider.CLAUDE)) {
                    throw new ProviderException("Effort " + effort.id() + " is not supported by Claude.");
                }
                command.add("--effort");
                command.add(effort.id());
            }
            if (resume) {
                command.add("--resume");
                command.add(sessionId);
            } else {
                command.add("--session-id");
                command.add(sessionId);
            }
            if (session.getMode() == SessionMode.AGENT) {
                consoleLease = consoleBridge.open(identity, session);
                command.add("--append-system-prompt");
                command.add(consoleLease.instructions());
                command.add("--dangerously-skip-permissions");
            } else {
                command.add("--safe-mode");
                command.add("--tools");
                command.add("");
                command.add("--strict-mcp-config");
                command.add("--disable-slash-commands");
            }

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            Map<String, String> env = builder.environment();
            env.put("CLAUDE_CODE_OAUTH_TOKEN", token);
            env.put("CLAUDE_CONFIG_DIR", configRoot.toString());
            env.put("DISABLE_UPDATES", "1");
            env.put("NO_COLOR", "1");
            env.remove("ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_AUTH_TOKEN");

            final ClaudeOutput output = new ClaudeOutput(progress);
            ProcessSupport.Result result = processes.run(
                    builder,
                    prompt,
                    session.getMode() == SessionMode.AGENT ? agentTimeout : chatTimeout,
                    output::accept,
                    registration(identity));
            if (result.timedOut) throw new ProviderException("Claude request timed out.");
            if (result.exitCode != 0) {
                throw new ProviderException("Claude exited with code " + result.exitCode + ": " + ProcessSupport.tail(result.lines));
            }
            String response = output.response();
            if (response.isEmpty()) response = "Claude completed without returning a text message.";
            return new PromptResult(response, output.sessionId == null ? sessionId : output.sessionId);
        } catch (IOException e) {
            throw new ProviderException("Could not start Claude: " + e.getMessage(), e);
        } finally {
            if (consoleLease != null) consoleLease.close();
        }
    }

    @Override
    public String accountStatus(final Identity identity) throws ProviderException {
        try {
            String token = secrets.get(identity, Provider.CLAUDE);
            if (token == null) return "not connected";
            Path executable = runtimes.resolve(Provider.CLAUDE);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "auth", "status", "--text");
            builder.environment().put("CLAUDE_CODE_OAUTH_TOKEN", token);
            builder.environment().put("DISABLE_UPDATES", "1");
            ProcessSupport.Result result = processes.run(builder, null, 30, null, registration(identity));
            return result.exitCode == 0 ? ProcessSupport.tail(result.lines) : "credential stored; provider status unavailable";
        } catch (IOException e) {
            throw new ProviderException(e.getMessage(), e);
        }
    }

    @Override
    public void startLink(final Identity identity, final ProgressListener progress) throws ProviderException {
        synchronized (pendingLinks) {
            if (pendingLinks.containsKey(identity.key())) {
                throw new ProviderException("A Claude account link is already pending.");
            }
        }
        ProcessSupport.Running running = null;
        ScheduledFuture<?> timeout = null;
        try {
            Path executable = runtimes.resolve(Provider.CLAUDE);
            Path identityRoot = sessions.identityRoot(identity);
            Path linkConfig = identityRoot.resolve("claude-link-config");
            Files.createDirectories(linkConfig);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "setup-token");
            builder.directory(identityRoot.toFile());
            builder.environment().put("CLAUDE_CONFIG_DIR", linkConfig.toString());
            builder.environment().put("DISABLE_UPDATES", "1");
            builder.environment().put("NO_COLOR", "1");
            builder.environment().remove("ANTHROPIC_API_KEY");
            builder.environment().remove("ANTHROPIC_AUTH_TOKEN");
            builder.environment().remove("CLAUDE_CODE_OAUTH_TOKEN");
            running = processes.start(builder);
            final ProcessSupport.Running linkedRunning = running;
            synchronized (pendingLinks) { pendingLinks.put(identity.key(), running); }
            active.add(identity.key(), running);
            timeout = linkTimer.schedule(linkedRunning::destroy, 10, TimeUnit.MINUTES);

            boolean foundToken = false;
            StringBuilder tokenBuffer = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(running.process.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String clean = ProcessSupport.stripControls(line);
                    tokenBuffer.append(clean);
                    Matcher matcher = OAUTH_TOKEN.matcher(tokenBuffer);
                    if (matcher.find()) {
                        secrets.put(identity, Provider.CLAUDE, matcher.group(1));
                        foundToken = true;
                        progress.onProgress("Claude account connected.");
                        tokenBuffer.setLength(0);
                        continue;
                    }
                    if (tokenBuffer.length() > 8192) tokenBuffer.delete(0, tokenBuffer.length() - 4096);
                    if (!clean.isEmpty() && !clean.toLowerCase(java.util.Locale.ROOT).contains("sk-ant")
                            && !OAUTH_TOKEN.matcher(clean).find()) {
                        progress.onProgress(redact(clean));
                    }
                }
            } finally {
                reader.close();
            }
            try {
                running.process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException("Claude account linking was interrupted", e);
            }
            if (!foundToken || running.process.exitValue() != 0) {
                throw new ProviderException("Claude account linking did not return a usable token.");
            }
        } catch (IOException e) {
            throw new ProviderException("Could not start Claude account linking: " + e.getMessage(), e);
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (running != null) {
                synchronized (pendingLinks) { pendingLinks.remove(identity.key()); }
                active.remove(identity.key(), running);
                running.closeInput();
            }
        }
    }

    @Override
    public boolean submitLinkCode(Identity identity, String code) throws ProviderException {
        if (code == null || code.trim().isEmpty() || code.length() > 4096) {
            throw new ProviderException("Invalid Claude login code.");
        }
        ProcessSupport.Running running;
        synchronized (pendingLinks) { running = pendingLinks.get(identity.key()); }
        if (running == null) return false;
        try {
            running.writeLine(code.trim());
            return true;
        } catch (IOException e) {
            throw new ProviderException("Could not submit Claude login code", e);
        }
    }

    @Override
    public void disconnect(Identity identity) throws ProviderException {
        try {
            cancel(identity);
            secrets.remove(identity, Provider.CLAUDE);
        } catch (IOException e) {
            throw new ProviderException("Could not remove Claude credential", e);
        }
    }

    @Override
    public boolean cancel(Identity identity) {
        synchronized (pendingLinks) { pendingLinks.remove(identity.key()); }
        return active.cancel(identity.key());
    }

    @Override
    public void shutdown() {
        active.cancelAll();
        processes.shutdown();
        linkTimer.shutdownNow();
    }

    private ProcessSupport.ProcessRegistration registration(final Identity identity) {
        return new ProcessSupport.ProcessRegistration() {
            @Override public void register(ProcessSupport.Running running) { active.add(identity.key(), running); }
            @Override public void clear(ProcessSupport.Running running) { active.remove(identity.key(), running); }
        };
    }

    private String redact(String value) {
        return value.replaceAll("(sk-ant-[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+", "$1...");
    }

    private static final class ClaudeOutput {
        private final ProgressListener progress;
        private String sessionId;
        private String result = "";
        private String lastAssistant = "";

        private ClaudeOutput(ProgressListener progress) {
            this.progress = progress;
        }

        private void accept(String line) {
            JsonElement parsed;
            try { parsed = new JsonParser().parse(line); }
            catch (RuntimeException ignored) { return; }
            if (!parsed.isJsonObject()) return;
            JsonObject object = parsed.getAsJsonObject();
            String type = string(object, "type");
            String reportedSession = string(object, "session_id");
            if (reportedSession != null) sessionId = reportedSession;
            if ("result".equals(type)) {
                String finalResult = string(object, "result");
                if (finalResult != null) result = finalResult;
                return;
            }
            if ("assistant".equals(type) && object.has("message") && object.get("message").isJsonObject()) {
                String text = contentText(object.getAsJsonObject("message").get("content"));
                if (!text.isEmpty()) lastAssistant = text;
            }
            if ("tool_use".equals(type)) {
                String name = string(object, "name");
                if (name != null) progress.onProgress("Claude is using " + name + ".");
            }
        }

        private String response() {
            return (result.isEmpty() ? lastAssistant : result).trim();
        }

        private static String contentText(JsonElement content) {
            if (content == null) return "";
            if (content.isJsonPrimitive()) return content.getAsString();
            if (!content.isJsonArray()) return "";
            StringBuilder output = new StringBuilder();
            JsonArray array = content.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if ("text".equals(string(object, "type"))) {
                    String text = string(object, "text");
                    if (text != null) output.append(text);
                } else if ("tool_use".equals(string(object, "type"))) {
                    String name = string(object, "name");
                    if (name != null) output.append("");
                }
            }
            return output.toString();
        }

        private static String string(JsonObject object, String field) {
            JsonElement value = object.get(field);
            return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
        }
    }
}
