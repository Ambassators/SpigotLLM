package dev.foreground.spigotllm.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foreground.spigotllm.console.AgentConsoleBridge;
import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.ReasoningEffort;
import dev.foreground.spigotllm.model.SessionMode;
import dev.foreground.spigotllm.runtime.RuntimeResolver;
import dev.foreground.spigotllm.session.SessionRecord;
import dev.foreground.spigotllm.session.SessionStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CodexAdapter implements ProviderAdapter {
    private final RuntimeResolver runtimes;
    private final SessionStore sessions;
    private final AgentConsoleBridge consoleBridge;
    private final AgentWorkspace agentWorkspace;
    private final int chatTimeout;
    private final int agentTimeout;
    private final ProcessSupport processes = new ProcessSupport();
    private final ActiveProcesses active = new ActiveProcesses();

    public CodexAdapter(RuntimeResolver runtimes, SessionStore sessions, AgentConsoleBridge consoleBridge,
                        AgentWorkspace agentWorkspace, int chatTimeout, int agentTimeout) {
        this.runtimes = runtimes;
        this.sessions = sessions;
        this.consoleBridge = consoleBridge;
        this.agentWorkspace = agentWorkspace;
        this.chatTimeout = chatTimeout;
        this.agentTimeout = agentTimeout;
    }

    @Override
    public PromptResult prompt(final Identity identity, SessionRecord session, String prompt,
                               final ProgressListener progress) throws ProviderException {
        AgentConsoleBridge.Lease consoleLease = null;
        try {
            Path executable = runtimes.resolve(Provider.CODEX);
            Path identityRoot = sessions.identityRoot(identity);
            Path codexHome = identityRoot.resolve("codex-home");
            Files.createDirectories(codexHome);
            boolean resume = session.getProviderSessionId() != null && !session.getProviderSessionId().isEmpty();
            Path cwd = workingDirectory(identityRoot, session);
            List<String> command = new ArrayList<String>();
            command.add(executable.toString());
            command.add("exec");
            command.add("--json");
            command.add("--skip-git-repo-check");
            command.add("--ignore-user-config");
            command.add("-c");
            command.add("cli_auth_credentials_store=\"file\"");
            command.add("-c");
            command.add("check_for_update_on_startup=false");
            ReasoningEffort effort = session.getReasoningEffort();
            if (!effort.isDefault()) {
                if (!effort.supports(Provider.CODEX)) {
                    throw new ProviderException("Effort " + effort.id() + " is not supported by Codex.");
                }
                command.add("-c");
                command.add("model_reasoning_effort=\"" + effort.id() + "\"");
            }
            if (session.getMode() == SessionMode.AGENT) {
                consoleLease = consoleBridge.open(identity, session);
                command.add("-c");
                command.add("developer_instructions="
                        + tomlString(consoleLease.instructions() + agentWorkspace.instructions()));
                command.add("--dangerously-bypass-approvals-and-sandbox");
            } else {
                command.add("-c");
                command.add("sandbox_mode=\"read-only\"");
                command.add("-c");
                command.add("approval_policy=\"never\"");
                if (!resume) {
                    command.add("--sandbox");
                    command.add("read-only");
                }
            }
            command.add("--cd");
            command.add(cwd.toString());
            if (resume) {
                command.add("resume");
                command.add(session.getProviderSessionId());
            }
            command.add("-");

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            Map<String, String> env = builder.environment();
            env.put("CODEX_HOME", codexHome.toAbsolutePath().toString());
            env.put("NO_COLOR", "1");
            env.remove("OPENAI_API_KEY");
            env.remove("CODEX_API_KEY");

            final CodexOutput output = new CodexOutput(progress);
            ProcessSupport.Result result = processes.run(
                    builder,
                    prompt,
                    session.getMode() == SessionMode.AGENT ? agentTimeout : chatTimeout,
                    output::accept,
                    registration(identity));
            if (result.timedOut) throw new ProviderException("Codex request timed out.");
            if (result.exitCode != 0) {
                throw new ProviderException("Codex exited with code " + result.exitCode + ": " + ProcessSupport.tail(result.lines));
            }
            String response = output.response();
            if (response.isEmpty()) response = "Codex completed without returning a text message.";
            return new PromptResult(response,
                    output.threadId == null ? session.getProviderSessionId() : output.threadId);
        } catch (IOException e) {
            throw new ProviderException("Could not start Codex: " + e.getMessage(), e);
        } finally {
            if (consoleLease != null) consoleLease.close();
        }
    }

    @Override
    public String accountStatus(final Identity identity) throws ProviderException {
        try {
            Path executable = runtimes.resolve(Provider.CODEX);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "login", "status",
                    "-c", "cli_auth_credentials_store=\"file\"");
            builder.environment().put("CODEX_HOME", sessions.identityRoot(identity).resolve("codex-home").toString());
            ProcessSupport.Result result = processes.run(builder, null, 30, null, registration(identity));
            return result.exitCode == 0 ? ProcessSupport.tail(result.lines) : "not connected";
        } catch (IOException e) {
            throw new ProviderException(e.getMessage(), e);
        }
    }

    @Override
    public void startLink(final Identity identity, final ProgressListener progress) throws ProviderException {
        try {
            Path executable = runtimes.resolve(Provider.CODEX);
            Path codexHome = sessions.identityRoot(identity).resolve("codex-home");
            Files.createDirectories(codexHome);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "login", "--device-auth",
                    "-c", "cli_auth_credentials_store=\"file\"");
            builder.environment().put("CODEX_HOME", codexHome.toString());
            builder.environment().put("NO_COLOR", "1");
            ProcessSupport.Result result = processes.run(builder, null, 600, line -> {
                String clean = ProcessSupport.stripControls(line);
                if (!clean.isEmpty()) progress.onProgress(redact(clean));
            }, registration(identity));
            if (result.timedOut) throw new ProviderException("Codex account linking timed out.");
            if (result.exitCode != 0) {
                throw new ProviderException("Codex login failed: " + ProcessSupport.tail(result.lines));
            }
            progress.onProgress("Codex account connected.");
        } catch (IOException e) {
            throw new ProviderException("Could not start Codex login: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean submitLinkCode(Identity identity, String code) {
        return false;
    }

    @Override
    public void disconnect(final Identity identity) throws ProviderException {
        try {
            Path executable = runtimes.resolve(Provider.CODEX);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "logout",
                    "-c", "cli_auth_credentials_store=\"file\"");
            builder.environment().put("CODEX_HOME", sessions.identityRoot(identity).resolve("codex-home").toString());
            ProcessSupport.Result result = processes.run(builder, null, 30, null, registration(identity));
            if (result.exitCode != 0) throw new ProviderException("Codex logout failed: " + ProcessSupport.tail(result.lines));
        } catch (IOException e) {
            throw new ProviderException(e.getMessage(), e);
        }
    }

    @Override
    public boolean cancel(Identity identity) {
        return active.cancel(identity.key());
    }

    @Override
    public void shutdown() {
        active.cancelAll();
        processes.shutdown();
    }

    private Path workingDirectory(Path identityRoot, SessionRecord session) throws IOException {
        if (session.getMode() == SessionMode.AGENT) {
            Files.createDirectories(agentWorkspace.root());
            return agentWorkspace.root();
        }
        Path isolated = identityRoot.resolve("chat-workspaces").resolve("codex").normalize();
        Files.createDirectories(isolated);
        return isolated;
    }

    private ProcessSupport.ProcessRegistration registration(final Identity identity) {
        return new ProcessSupport.ProcessRegistration() {
            @Override
            public void register(ProcessSupport.Running running) {
                active.add(identity.key(), running);
            }

            @Override
            public void clear(ProcessSupport.Running running) {
                active.remove(identity.key(), running);
            }
        };
    }

    private String redact(String line) {
        return line.replaceAll("(?i)(sk-[A-Za-z0-9_-]{12})[A-Za-z0-9_-]+", "$1...");
    }

    private String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    private static final class CodexOutput {
        private final ProgressListener progress;
        private String threadId;
        private String lastMessage = "";

        private CodexOutput(ProgressListener progress) {
            this.progress = progress;
        }

        private void accept(String line) {
            JsonElement parsed;
            try {
                parsed = new JsonParser().parse(line);
            } catch (RuntimeException ignored) {
                return;
            }
            if (!parsed.isJsonObject()) return;
            JsonObject object = parsed.getAsJsonObject();
            String type = getString(object, "type");
            if ("thread.started".equals(type)) {
                threadId = firstString(object, "thread_id", "threadId", "id");
                progress.onProgress("Codex session started.");
                return;
            }
            JsonObject item = object.has("item") && object.get("item").isJsonObject()
                    ? object.getAsJsonObject("item") : null;
            if (item != null) {
                String itemType = getString(item, "type");
                if ("agent_message".equals(itemType) || "assistant_message".equals(itemType)) {
                    String text = extractText(item);
                    if (!text.isEmpty()) lastMessage = text;
                } else if (type != null && type.endsWith("started")) {
                    String summary = firstString(item, "command", "name", "path");
                    if (summary != null) progress.onProgress("Codex: " + safeSummary(summary));
                }
            }
        }

        private String response() {
            return lastMessage.trim();
        }

        private static String extractText(JsonObject object) {
            String direct = firstString(object, "text", "message", "content");
            if (direct != null) return direct;
            JsonElement content = object.get("content");
            if (content != null && content.isJsonArray()) {
                StringBuilder result = new StringBuilder();
                JsonArray array = content.getAsJsonArray();
                for (JsonElement element : array) {
                    if (element.isJsonObject()) {
                        String text = firstString(element.getAsJsonObject(), "text", "content");
                        if (text != null) result.append(text);
                    }
                }
                return result.toString();
            }
            return "";
        }

        private static String getString(JsonObject object, String field) {
            JsonElement value = object.get(field);
            return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
        }

        private static String firstString(JsonObject object, String... fields) {
            for (String field : fields) {
                String value = getString(object, field);
                if (value != null) return value;
            }
            return null;
        }

        private static String safeSummary(String input) {
            String clean = ProcessSupport.stripControls(input).replaceAll("\\s+", " ");
            return clean.length() <= 140 ? clean : clean.substring(0, 137) + "...";
        }
    }
}
