package dev.foreground.spigotllm;

import dev.foreground.spigotllm.access.AccessStore;
import dev.foreground.spigotllm.access.AuthorizedOperator;
import dev.foreground.spigotllm.agent.AgentToolRuntime;
import dev.foreground.spigotllm.agent.runtime.ResourceMetadata;
import dev.foreground.spigotllm.account.LinkCodeCapture;
import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.ReasoningEffort;
import dev.foreground.spigotllm.model.SessionMode;
import dev.foreground.spigotllm.output.PrivateMessenger;
import dev.foreground.spigotllm.provider.ProgressListener;
import dev.foreground.spigotllm.provider.ProviderAdapter;
import dev.foreground.spigotllm.provider.ProviderException;
import dev.foreground.spigotllm.runtime.RuntimeInstaller;
import dev.foreground.spigotllm.session.SessionRecord;
import dev.foreground.spigotllm.session.SessionStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SpigotLlmCommand implements CommandExecutor, TabCompleter {
    private static final int MAX_PROMPT_LENGTH = 32000;

    private final AccessStore access;
    private final SessionStore sessions;
    private final RuntimeInstaller runtimes;
    private final PromptCoordinator coordinator;
    private final PrivateMessenger messenger;
    private final LinkCodeCapture codeCapture;
    private final AgentToolRuntime toolRuntime;

    public SpigotLlmCommand(AccessStore access, SessionStore sessions, RuntimeInstaller runtimes,
                            PromptCoordinator coordinator, PrivateMessenger messenger,
                            LinkCodeCapture codeCapture, AgentToolRuntime toolRuntime) {
        this.access = access;
        this.sessions = sessions;
        this.runtimes = runtimes;
        this.coordinator = coordinator;
        this.messenger = messenger;
        this.codeCapture = codeCapture;
        this.toolRuntime = toolRuntime;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if ("spigotllm".equals(commandName) && args.length > 0 && "access".equalsIgnoreCase(args[0])) {
            handleAccess(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (!authorized(sender)) return true;

        if ("codex".equals(commandName)) {
            handleDirectPrompt(sender, Provider.CODEX, args);
            return true;
        }
        if ("claude".equals(commandName)) {
            handleDirectPrompt(sender, Provider.CLAUDE, args);
            return true;
        }
        handleMain(sender, args);
        return true;
    }

    private boolean authorized(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return true;
        if (!(sender instanceof Player)) {
            messenger.error(sender, "Only the server console or an authorized operator may use SpigotLLM.");
            return false;
        }
        Player player = (Player) sender;
        if (!player.isOp() || !access.isAuthorized(player.getUniqueId())) {
            messenger.error(sender, "You must be an OP explicitly authorized by the server console.");
            return false;
        }
        return true;
    }

    private void handleDirectPrompt(CommandSender sender, Provider provider, String[] args) {
        if (args.length > 0 && "threads".equalsIgnoreCase(args[0])) {
            handleProviderThreadList(sender, provider, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "thread".equalsIgnoreCase(args[0])) {
            handleProviderThread(sender, provider, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && ("effort".equalsIgnoreCase(args[0]) || "level".equalsIgnoreCase(args[0]))) {
            handleDirectEffort(sender, provider, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SessionMode mode = provider == Provider.CODEX ? SessionMode.AGENT : SessionMode.CHAT;
        int promptStart = 0;
        if (args.length > 0 && SessionMode.parse(args[0]) != null) {
            mode = SessionMode.parse(args[0]);
            promptStart = 1;
        }
        if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
            messenger.error(sender, "Codex chat mode has been removed. Use /codex <prompt>; Codex now runs as an agent by default.");
            return;
        }
        if (args.length <= promptStart) {
            messenger.error(sender, provider == Provider.CODEX
                    ? "Usage: /codex <prompt>"
                    : "Usage: /claude [chat|agent] <prompt>");
            return;
        }
        submitPrompt(sender, provider, mode, join(args, promptStart));
    }

    private void handleMain(CommandSender sender, String[] args) {
        if (args.length == 0) {
            shortHelp(sender);
            return;
        }
        if ("help".equalsIgnoreCase(args[0])) {
            fullHelp(sender);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("prompt".equals(sub)) {
            if (args.length < 4) {
                messenger.error(sender, "Usage: /sllm prompt <codex|claude> <chat|agent> <prompt>");
                return;
            }
            Provider provider = requireProvider(sender, args[1]);
            SessionMode mode = requireMode(sender, args[2]);
            if (provider != null && mode != null) submitPrompt(sender, provider, mode, join(args, 3));
        } else if ("account".equals(sub)) {
            handleAccount(sender, Arrays.copyOfRange(args, 1, args.length));
        } else if ("session".equals(sub)) {
            handleSession(sender, Arrays.copyOfRange(args, 1, args.length));
        } else if ("thread".equals(sub)) {
            if (args.length < 2) {
                messenger.error(sender, "Usage: /sllm thread <codex|claude> <current|new|switch|list|rename|delete> ...");
                return;
            }
            Provider provider = requireProvider(sender, args[1]);
            if (provider != null) {
                handleProviderThread(sender, provider, Arrays.copyOfRange(args, 2, args.length));
            }
        } else if ("threads".equals(sub)) {
            handleThreadOverview(sender, Arrays.copyOfRange(args, 1, args.length));
        } else if ("effort".equals(sub) || "level".equals(sub)) {
            handleEffort(sender, Arrays.copyOfRange(args, 1, args.length));
        } else if ("runtime".equals(sub)) {
            handleRuntime(sender, Arrays.copyOfRange(args, 1, args.length));
        } else if ("tools".equals(sub)) {
            handleTools(sender, Arrays.copyOfRange(args, 1, args.length));
        } else if ("cancel".equals(sub)) {
            Provider provider = args.length > 1 && !"all".equalsIgnoreCase(args[1])
                    ? requireProvider(sender, args[1]) : null;
            if (args.length > 1 && provider == null && !"all".equalsIgnoreCase(args[1])) return;
            boolean cancelled = coordinator.cancel(Identity.from(sender), provider);
            if (cancelled) messenger.success(sender, "Cancellation requested.");
            else messenger.info(sender, "No matching provider process is running.");
        } else if ("more".equals(sub)) {
            int page = 2;
            if (args.length > 1) {
                try { page = Integer.parseInt(args[1]); }
                catch (NumberFormatException e) {
                    messenger.error(sender, "Page must be a number.");
                    return;
                }
            }
            messenger.more(sender, page);
        } else {
            messenger.error(sender, "Unknown subcommand. Use /sllm help.");
        }
    }

    private void handleTools(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        try {
            if ("list".equals(action)) {
                List<ResourceMetadata> entries = toolRuntime.listAll();
                messenger.info(sender, "Agent runtime resources: " + entries.size());
                for (ResourceMetadata entry : entries) {
                    messenger.info(sender, entry.getOwner() + " / " + entry.getId() + " ["
                            + entry.getType() + ", " + entry.getLifecycle() + ", "
                            + entry.getState().name().toLowerCase(Locale.ROOT) + "]");
                }
                return;
            }
            if ("purge".equals(action)) {
                if (!(sender instanceof ConsoleCommandSender)) {
                    messenger.error(sender, "Only the real server console can purge agent runtime resources.");
                    return;
                }
                if (args.length != 3 || !("transient".equalsIgnoreCase(args[1]) || "all".equalsIgnoreCase(args[1]))
                        || !"confirm".equalsIgnoreCase(args[2])) {
                    messenger.error(sender, "Usage: /sllm tools purge <transient|all> confirm");
                    return;
                }
                int removed = toolRuntime.operatorPurge("all".equalsIgnoreCase(args[1]));
                messenger.success(sender, "Removed " + removed + " agent runtime resources.");
                return;
            }
            if (!("inspect".equals(action) || "enable".equals(action) || "disable".equals(action)
                    || "remove".equals(action)) || args.length != 3) {
                messenger.error(sender, "Usage: /sllm tools <list|inspect|enable|disable|remove> [owner] [resource-id]");
                return;
            }
            ResourceMetadata result = "inspect".equals(action) ? toolRuntime.operatorInspect(args[1], args[2])
                    : "enable".equals(action) ? toolRuntime.operatorEnable(args[1], args[2])
                    : "disable".equals(action) ? toolRuntime.operatorDisable(args[1], args[2])
                    : toolRuntime.operatorRemove(args[1], args[2]);
            messenger.success(sender, result.getOwner() + " / " + result.getId() + " is "
                    + result.getState().name().toLowerCase(Locale.ROOT) + ".");
            if (result.getFailure() != null) messenger.error(sender, result.getFailure());
        } catch (RuntimeException e) {
            messenger.error(sender, e.getMessage() == null ? "Agent runtime operation failed." : e.getMessage());
        }
    }

    private void submitPrompt(CommandSender sender, Provider provider, SessionMode mode, String prompt) {
        String clean = prompt == null ? "" : prompt.trim();
        if (clean.isEmpty()) {
            messenger.error(sender, "Prompt cannot be empty.");
        } else if (clean.length() > MAX_PROMPT_LENGTH) {
            messenger.error(sender, "Prompt is too long (maximum " + MAX_PROMPT_LENGTH + " characters).");
        } else if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
            messenger.error(sender, "Codex chat mode has been removed. Use Codex agent mode.");
        } else {
            Identity identity = Identity.from(sender);
            if (provider == Provider.CODEX) {
                try {
                    sessions.prepareCodexAgentPrompt(identity, clean);
                } catch (IOException e) {
                    messenger.error(sender, "Could not prepare the Codex thread: " + e.getMessage());
                    return;
                }
            }
            coordinator.submit(sender, identity, provider, mode, clean);
        }
    }

    private void handleAccess(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            messenger.error(sender, "Only the real server console can change the authorized operator list.");
            return;
        }
        if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
            List<AuthorizedOperator> entries = access.list();
            messenger.info(sender, "Authorized operators: " + entries.size());
            for (AuthorizedOperator entry : entries) {
                String name = entry.getLastKnownName() == null ? "unknown" : entry.getLastKnownName();
                messenger.info(sender, name + " - " + entry.getUuid());
            }
            return;
        }
        if (args.length < 2 || !("add".equalsIgnoreCase(args[0]) || "remove".equalsIgnoreCase(args[0]))) {
            messenger.error(sender, "Usage: /sllm access <add|remove|list> <online-player|uuid>");
            return;
        }
        AccessTarget target = resolveAccessTarget(args[1], "remove".equalsIgnoreCase(args[0]));
        if (target == null) {
            messenger.error(sender, "Player not found. They must be online, previously seen, already listed, or supplied by UUID.");
            return;
        }
        try {
            if ("add".equalsIgnoreCase(args[0])) {
                boolean added = access.add(target.uuid, target.name);
                messenger.success(sender, (added ? "Authorized " : "Updated ") + target.name + " (" + target.uuid + ").");
            } else {
                boolean removed = access.remove(target.uuid);
                if (removed) messenger.success(sender, "Removed " + target.name + " from the allowlist.");
                else messenger.info(sender, target.name + " was not on the allowlist.");
            }
        } catch (IOException e) {
            messenger.error(sender, "Could not save the allowlist: " + e.getMessage());
        }
    }

    private AccessTarget resolveAccessTarget(String input, boolean allowListedName) {
        try {
            UUID uuid = UUID.fromString(input);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            return new AccessTarget(uuid, offline.getName() == null ? uuid.toString() : offline.getName());
        } catch (IllegalArgumentException ignored) { }
        if (allowListedName) {
            for (AuthorizedOperator entry : access.list()) {
                if (entry.getLastKnownName() != null && entry.getLastKnownName().equalsIgnoreCase(input)) {
                    return new AccessTarget(UUID.fromString(entry.getUuid()), entry.getLastKnownName());
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(input)) {
                return new AccessTarget(player.getUniqueId(), player.getName());
            }
        }
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(input)) {
                return new AccessTarget(player.getUniqueId(), player.getName());
            }
        }
        return null;
    }

    private void handleAccount(final CommandSender sender, String[] args) {
        if (args.length == 0) {
            messenger.error(sender, "Usage: /sllm account <connect|code|status|disconnect> <codex|claude>");
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if ("code".equals(action) && args.length > 1 && "cancel".equalsIgnoreCase(args[1]) && sender instanceof Player) {
            if (codeCapture.cancel((Player) sender)) messenger.success(sender, "Private code capture cancelled.");
            else messenger.info(sender, "No private code capture was armed.");
            return;
        }
        if (args.length < 2) {
            messenger.error(sender, "Usage: /sllm account " + action + " <codex|claude>");
            return;
        }
        final Provider provider = requireProvider(sender, args[1]);
        if (provider == null) return;
        final Identity identity = Identity.from(sender);
        final ProviderAdapter adapter = coordinator.adapter(provider);
        if ("code".equals(action)) {
            if (sender instanceof Player) {
                if (provider != Provider.CLAUDE) {
                    messenger.error(sender, "Codex device login does not accept a pasted code in Minecraft.");
                    return;
                }
                if (args.length > 2) {
                    messenger.error(sender, "For privacy, do not put the code on the command line. Run /sllm account code claude, then send it as your next chat message.");
                    return;
                }
                codeCapture.arm((Player) sender, provider);
            } else {
                if (args.length < 3) {
                    messenger.error(sender, "Usage: /sllm account code claude <code>");
                    return;
                }
                try {
                    if (adapter.submitLinkCode(identity, join(args, 2))) messenger.success(sender, "Login code submitted.");
                    else messenger.error(sender, "No " + provider.id() + " login is waiting for a code.");
                } catch (ProviderException e) {
                    messenger.error(sender, e.getMessage());
                }
            }
            return;
        }
        if ("connect".equals(action)) {
            messenger.info(sender, "Starting private " + provider.id() + " account linking...");
            coordinator.executeBackground(sender, new Runnable() {
                @Override public void run() {
                    try {
                        adapter.startLink(identity, new ProgressListener() {
                            @Override public void onProgress(String message) { messenger.info(sender, message); }
                        });
                    } catch (ProviderException e) {
                        messenger.error(sender, e.getMessage());
                    }
                }
            });
        } else if ("status".equals(action)) {
            coordinator.executeBackground(sender, new Runnable() {
                @Override public void run() {
                    try { messenger.info(sender, provider.id() + ": " + adapter.accountStatus(identity)); }
                    catch (ProviderException e) { messenger.error(sender, e.getMessage()); }
                }
            });
        } else if ("disconnect".equals(action)) {
            if (args.length < 3 || !"confirm".equalsIgnoreCase(args[2])) {
                messenger.error(sender, "This removes the stored login. Use /sllm account disconnect " + provider.id() + " confirm");
                return;
            }
            coordinator.executeBackground(sender, new Runnable() {
                @Override public void run() {
                    try {
                        adapter.disconnect(identity);
                        messenger.success(sender, provider.id() + " account disconnected.");
                    } catch (ProviderException e) { messenger.error(sender, e.getMessage()); }
                }
            });
        } else {
            messenger.error(sender, "Unknown account action.");
        }
    }

    private void handleSession(CommandSender sender, String[] args) {
        if (args.length == 0) {
            messenger.error(sender, "Usage: /sllm session <new|list|use|rename|delete> ...");
            return;
        }
        Identity identity = Identity.from(sender);
        String action = args[0].toLowerCase(Locale.ROOT);
        try {
            if ("list".equals(action)) {
                List<SessionRecord> records = sessions.list(identity);
                if (records.isEmpty()) messenger.info(sender, "No named sessions yet. A default is created on first prompt.");
                for (SessionRecord record : records) {
                    String effort = record.getReasoningEffort().isDefault()
                            ? "provider-default" : record.getReasoningEffort().id();
                    messenger.info(sender, record.getProvider().id() + " " + record.getMode().id() + " "
                            + record.getDisplayName() + " [effort=" + effort + "]");
                }
                return;
            }
            if (coordinator.isBusy(identity)) {
                messenger.error(sender, "Cancel or wait for your active prompt before changing sessions.");
                return;
            }
            if (args.length < 4) {
                messenger.error(sender, "Usage: /sllm session " + action + " <codex|claude> <chat|agent> <name> ...");
                return;
            }
            Provider provider = requireProvider(sender, args[1]);
            SessionMode mode = requireMode(sender, args[2]);
            if (provider == null || mode == null) return;
            if ("new".equals(action)) {
                sessions.create(identity, provider, mode, args[3]);
                messenger.success(sender, "Created and selected session " + args[3] + ".");
            } else if ("use".equals(action)) {
                if (sessions.use(identity, provider, mode, args[3])) messenger.success(sender, "Selected session " + args[3] + ".");
                else messenger.error(sender, "Session not found.");
            } else if ("rename".equals(action)) {
                if (args.length < 5) {
                    messenger.error(sender, "Usage: /sllm session rename <provider> <mode> <old> <new>");
                } else if (sessions.rename(identity, provider, mode, args[3], args[4])) {
                    messenger.success(sender, "Renamed session to " + args[4] + ".");
                } else messenger.error(sender, "Old session was not found or the new name already exists.");
            } else if ("delete".equals(action)) {
                if (args.length < 5 || !"confirm".equalsIgnoreCase(args[4])) {
                    messenger.error(sender, "Use /sllm session delete " + provider.id() + " " + mode.id() + " " + args[3] + " confirm");
                } else if (sessions.delete(identity, provider, mode, args[3]) != null) {
                    messenger.success(sender, "Deleted session " + args[3] + ".");
                } else messenger.error(sender, "Session not found.");
            } else messenger.error(sender, "Unknown session action.");
        } catch (IllegalArgumentException e) {
            messenger.error(sender, e.getMessage());
        } catch (IOException e) {
            messenger.error(sender, "Could not update sessions: " + e.getMessage());
        }
    }

    private void handleEffort(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            messenger.error(sender, "Usage: /sllm effort <codex|claude> <chat|agent> [level|default]");
            return;
        }
        Provider provider = requireProvider(sender, args[0]);
        SessionMode mode = requireMode(sender, args[1]);
        if (provider == null || mode == null) return;
        if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
            messenger.error(sender, "Codex chat mode has been removed.");
            return;
        }
        Identity identity = Identity.from(sender);
        try {
            SessionRecord session = sessions.getOrCreateActive(identity, provider, mode);
            if (args.length == 2) {
                String current = session.getReasoningEffort().isDefault()
                        ? "provider-default (no SpigotLLM override)" : session.getReasoningEffort().id();
                messenger.info(sender, provider.id() + " " + mode.id() + " session " + session.getDisplayName()
                        + " uses " + current + ". Options: " + ReasoningEffort.choices(provider));
                return;
            }
            if (coordinator.isBusy(identity)) {
                messenger.error(sender, "Cancel or wait for your active prompt before changing its effort.");
                return;
            }
            ReasoningEffort effort = ReasoningEffort.parse(args[2]);
            if (effort == null || !effort.supports(provider)) {
                messenger.error(sender, "Effort for " + provider.id() + " must be: "
                        + ReasoningEffort.choices(provider) + ".");
                return;
            }
            sessions.updateReasoningEffort(identity, session, effort);
            String selected = effort.isDefault() ? "provider-default" : effort.id();
            messenger.success(sender, "Set " + provider.id() + " " + mode.id() + " session "
                    + session.getDisplayName() + " to " + selected + " effort.");
        } catch (IOException e) {
            messenger.error(sender, "Could not update session effort: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            messenger.error(sender, e.getMessage());
        }
    }

    private void handleDirectEffort(CommandSender sender, Provider provider, String[] args) {
        SessionMode mode = provider == Provider.CODEX ? SessionMode.AGENT : SessionMode.CHAT;
        int valueIndex = 0;
        if (args.length > 0 && SessionMode.parse(args[0]) != null) {
            mode = SessionMode.parse(args[0]);
            valueIndex = 1;
        }
        if (args.length - valueIndex > 1) {
            messenger.error(sender, provider == Provider.CODEX
                    ? "Usage: /codex effort [level|default]"
                    : "Usage: /claude effort [chat|agent] [level|default]");
            return;
        }
        if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
            messenger.error(sender, "Codex chat mode has been removed.");
            return;
        }
        List<String> forwarded = new ArrayList<String>();
        forwarded.add(provider.id());
        forwarded.add(mode.id());
        if (args.length > valueIndex) forwarded.add(args[valueIndex]);
        handleEffort(sender, forwarded.toArray(new String[forwarded.size()]));
    }

    private void handleProviderThread(CommandSender sender, Provider provider, String[] args) {
        String action = args.length == 0 ? "current" : args[0].toLowerCase(Locale.ROOT);
        if ("current".equals(action)) {
            if (args.length > 2) {
                threadUsage(sender, provider);
                return;
            }
            SessionMode mode = args.length == 2 ? requireMode(sender, args[1])
                    : provider == Provider.CODEX ? SessionMode.AGENT : SessionMode.CHAT;
            if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
                messenger.error(sender, "Codex chat mode has been removed.");
                return;
            }
            if (mode != null) showCurrentThread(sender, provider, mode);
            return;
        }
        if ("list".equals(action)) {
            handleProviderThreadList(sender, provider, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!("new".equals(action) || "switch".equals(action) || "use".equals(action)
                || "rename".equals(action) || "delete".equals(action))) {
            threadUsage(sender, provider);
            return;
        }
        Identity identity = Identity.from(sender);
        if (coordinator.isBusy(identity)) {
            messenger.error(sender, "Cancel or wait for your active prompt before changing threads.");
            return;
        }
        int valueIndex = 1;
        SessionMode mode = provider == Provider.CODEX ? SessionMode.AGENT : SessionMode.CHAT;
        if (args.length > valueIndex && SessionMode.parse(args[valueIndex]) != null) {
            mode = SessionMode.parse(args[valueIndex]);
            valueIndex++;
        }
        int remaining = args.length - valueIndex;
        if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
            messenger.error(sender, "Codex chat mode has been removed.");
            return;
        }
        try {
            if ("new".equals(action)) {
                if (provider == Provider.CODEX) {
                    if (remaining != 0) {
                        messenger.error(sender, "Usage: /codex thread new");
                        return;
                    }
                    sessions.createCodexAgentThread(identity);
                    messenger.success(sender, "Started a new Codex thread. Its name will come from your first message.");
                } else {
                    if (remaining != 1) {
                        messenger.error(sender, "Usage: /claude thread new [chat|agent] <name>");
                        return;
                    }
                    SessionRecord created = sessions.create(identity, provider, mode, args[valueIndex]);
                    messenger.success(sender, "Created and switched to " + provider.id() + " " + mode.id()
                            + " thread " + created.getName() + ".");
                }
            } else if ("switch".equals(action) || "use".equals(action)) {
                if (remaining != 1) {
                    messenger.error(sender, "Usage: /" + provider.id() + " thread switch [chat|agent] <name>");
                    return;
                }
                if (sessions.use(identity, provider, mode, args[valueIndex])) {
                    messenger.success(sender, "Switched to " + provider.id() + " " + mode.id()
                            + " thread " + args[valueIndex] + ".");
                } else {
                    messenger.error(sender, "Thread not found. Use /" + provider.id() + " threads " + mode.id() + ".");
                }
            } else if ("rename".equals(action)) {
                if (remaining < 1 || remaining > 2) {
                    messenger.error(sender, "Usage: /" + provider.id() + " thread rename [chat|agent] [old-name] <new-name>");
                    return;
                }
                String oldName;
                String newName;
                if (remaining == 1) {
                    oldName = sessions.getOrCreateActive(identity, provider, mode).getName();
                    newName = args[valueIndex];
                } else {
                    oldName = args[valueIndex];
                    newName = args[valueIndex + 1];
                }
                if (sessions.rename(identity, provider, mode, oldName, newName)) {
                    messenger.success(sender, "Renamed thread " + oldName + " to " + newName + ".");
                } else {
                    messenger.error(sender, "Thread not found, or the new name already exists.");
                }
            } else {
                if (remaining != 2 || !"confirm".equalsIgnoreCase(args[valueIndex + 1])) {
                    messenger.error(sender, "Use /" + provider.id() + " thread delete " + mode.id()
                            + " <name> confirm");
                    return;
                }
                SessionRecord deleted = sessions.delete(identity, provider, mode, args[valueIndex]);
                if (deleted != null) messenger.success(sender, "Deleted thread " + deleted.getName() + ".");
                else messenger.error(sender, "Thread not found.");
            }
        } catch (IllegalArgumentException e) {
            messenger.error(sender, e.getMessage());
        } catch (IOException e) {
            messenger.error(sender, "Could not update threads: " + e.getMessage());
        }
    }

    private void handleProviderThreadList(CommandSender sender, Provider provider, String[] args) {
        if (args.length > 1) {
            messenger.error(sender, "Usage: /" + provider.id() + " threads [chat|agent]");
            return;
        }
        SessionMode mode = args.length == 1 ? requireMode(sender, args[0])
                : provider == Provider.CODEX ? SessionMode.AGENT : null;
        if (args.length == 1 && mode == null) return;
        if (provider == Provider.CODEX && mode == SessionMode.CHAT) {
            messenger.error(sender, "Codex chat mode has been removed.");
            return;
        }
        listThreads(sender, provider, mode);
    }

    private void handleThreadOverview(CommandSender sender, String[] args) {
        if (args.length > 2) {
            messenger.error(sender, "Usage: /sllm threads [codex|claude] [chat|agent]");
            return;
        }
        Provider provider = null;
        SessionMode mode = null;
        if (args.length > 0) {
            provider = Provider.parse(args[0]);
            mode = SessionMode.parse(args[0]);
            if (provider == null && mode == null) {
                messenger.error(sender, "Filter must be codex, claude, chat, or agent.");
                return;
            }
        }
        if (args.length == 2) {
            if (provider == null) {
                messenger.error(sender, "Use /sllm threads <codex|claude> <chat|agent>.");
                return;
            }
            mode = requireMode(sender, args[1]);
            if (mode == null) return;
        }
        listThreads(sender, provider, mode);
    }

    private void listThreads(CommandSender sender, Provider provider, SessionMode mode) {
        Identity identity = Identity.from(sender);
        try {
            List<SessionRecord> records = sessions.list(identity);
            showThreadMenuHeader(sender, provider, mode);
            int shown = 0;
            for (SessionRecord record : records) {
                if (record.getProvider() == Provider.CODEX && record.getMode() == SessionMode.CHAT) continue;
                if (provider != null && record.getProvider() != provider) continue;
                if (mode != null && record.getMode() != mode) continue;
                boolean active = sessions.isActive(identity, record);
                showThreadMenuEntry(sender, record, active);
                shown++;
            }
            if (shown == 0) {
                messenger.info(sender, "No matching threads yet. Use one of the new-thread buttons above.");
            }
        } catch (IOException e) {
            messenger.error(sender, "Could not list threads: " + e.getMessage());
        }
    }

    private void showThreadMenuHeader(CommandSender sender, Provider provider, SessionMode mode) {
        List<PrivateMessenger.Part> parts = new ArrayList<PrivateMessenger.Part>();
        parts.add(PrivateMessenger.Part.bold(ChatColor.AQUA, "Threads "));
        if ((provider == null || provider == Provider.CODEX) && (mode == null || mode == SessionMode.AGENT)) {
            parts.add(PrivateMessenger.Part.run(ChatColor.GREEN, "[+ Codex]", "/codex thread new",
                    "Start a new Codex agent thread"));
        }
        if (provider == null || provider == Provider.CLAUDE) {
            if (mode == null || mode == SessionMode.CHAT) {
                parts.add(PrivateMessenger.Part.text(ChatColor.GRAY, " "));
                parts.add(PrivateMessenger.Part.suggest(ChatColor.GREEN, "[+ Claude Chat]",
                        "/claude thread new chat ", "Click, then type a thread name"));
            }
            if (mode == null || mode == SessionMode.AGENT) {
                parts.add(PrivateMessenger.Part.text(ChatColor.GRAY, " "));
                parts.add(PrivateMessenger.Part.suggest(ChatColor.GREEN, "[+ Claude Agent]",
                        "/claude thread new agent ", "Click, then type a thread name"));
            }
        }
        messenger.interactive(sender, parts.toArray(new PrivateMessenger.Part[parts.size()]));
    }

    private void showThreadMenuEntry(CommandSender sender, SessionRecord record, boolean active) {
        String provider = record.getProvider().id();
        String mode = record.getMode().id();
        String id = record.getName();
        String switchCommand = record.getProvider() == Provider.CODEX
                ? "/codex thread switch " + id
                : "/claude thread switch " + mode + " " + id;
        String renameCommand = record.getProvider() == Provider.CODEX
                ? "/codex thread rename " + id + " "
                : "/claude thread rename " + mode + " " + id + " ";
        String deleteCommand = record.getProvider() == Provider.CODEX
                ? "/codex thread delete " + id + " confirm"
                : "/claude thread delete " + mode + " " + id + " confirm";
        String hover = provider + " " + mode + " | id: " + id + " | effort: " + effortLabel(record);

        List<PrivateMessenger.Part> parts = new ArrayList<PrivateMessenger.Part>();
        parts.add(PrivateMessenger.Part.text(active ? ChatColor.GREEN : ChatColor.DARK_GRAY,
                active ? "● " : "○ "));
        if (active) {
            parts.add(PrivateMessenger.Part.bold(ChatColor.GREEN, record.getDisplayName()));
        } else {
            parts.add(PrivateMessenger.Part.run(ChatColor.AQUA, record.getDisplayName(), switchCommand,
                    "Click to switch | " + hover));
        }
        parts.add(PrivateMessenger.Part.text(ChatColor.DARK_GRAY, " · "));
        parts.add(PrivateMessenger.Part.text(ChatColor.GRAY, provider + "/" + mode));
        parts.add(PrivateMessenger.Part.text(ChatColor.GRAY, " "));
        if (active) {
            parts.add(PrivateMessenger.Part.text(ChatColor.GREEN, "[Current]"));
        } else {
            parts.add(PrivateMessenger.Part.run(ChatColor.AQUA, "[Switch]", switchCommand, "Switch to this thread"));
        }
        parts.add(PrivateMessenger.Part.text(ChatColor.GRAY, " "));
        parts.add(PrivateMessenger.Part.suggest(ChatColor.YELLOW, "[Rename]", renameCommand,
                "Click, then type the new name"));
        parts.add(PrivateMessenger.Part.text(ChatColor.GRAY, " "));
        parts.add(PrivateMessenger.Part.suggest(ChatColor.RED, "[Delete]", deleteCommand,
                "Click to prepare the confirmed delete command"));
        messenger.interactive(sender, parts.toArray(new PrivateMessenger.Part[parts.size()]));
    }

    private void showCurrentThread(CommandSender sender, Provider provider, SessionMode mode) {
        try {
            SessionRecord current = sessions.getOrCreateActive(Identity.from(sender), provider, mode);
            messenger.info(sender, "Current " + provider.id() + " thread: "
                    + current.getDisplayName() + " [effort=" + effortLabel(current) + "].");
        } catch (IOException e) {
            messenger.error(sender, "Could not load the current thread: " + e.getMessage());
        }
    }

    private String effortLabel(SessionRecord record) {
        return record.getReasoningEffort().isDefault()
                ? "provider-default" : record.getReasoningEffort().id();
    }

    private void threadUsage(CommandSender sender, Provider provider) {
        if (provider == Provider.CODEX) {
            messenger.info(sender, "/codex threads - list agent threads");
            messenger.info(sender, "/codex thread new - start a new automatically named agent thread");
        } else {
            messenger.info(sender, "/claude threads [chat|agent] - list threads");
            messenger.info(sender, "/claude thread <current|new|switch|rename|delete> [chat|agent] ...");
        }
    }

    private void handleRuntime(final CommandSender sender, String[] args) {
        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            List<Provider> providers = args.length > 1 ? parseProviders(sender, args[1]) : Arrays.asList(Provider.values());
            if (providers == null) return;
            for (Provider provider : providers) {
                try { messenger.info(sender, runtimes.status(provider)); }
                catch (IOException e) { messenger.error(sender, e.getMessage()); }
            }
            return;
        }
        final String action = args[0].toLowerCase(Locale.ROOT);
        if (!("install".equals(action) || "update".equals(action) || "rollback".equals(action)) || args.length < 2) {
            messenger.error(sender, "Usage: /sllm runtime <install|update|rollback|status> <codex|claude|all>");
            return;
        }
        final List<Provider> providers = parseProviders(sender, args[1]);
        if (providers == null) return;
        if ("rollback".equals(action) && (args.length < 3 || !"confirm".equalsIgnoreCase(args[2]))) {
            messenger.error(sender, "Use /sllm runtime rollback " + args[1] + " confirm");
            return;
        }
        if (!coordinator.beginRuntimeOperation()) {
            messenger.error(sender, "Wait for active prompts or the current runtime operation to finish.");
            return;
        }
        boolean queued = coordinator.executeBackground(sender, new Runnable() {
            @Override public void run() {
                try {
                    for (Provider provider : providers) {
                        if ("rollback".equals(action)) {
                            messenger.success(sender, "Rolled " + provider.id() + " back to " + runtimes.rollback(provider) + ".");
                        } else {
                            String version = runtimes.install(provider, new ProgressListener() {
                                @Override public void onProgress(String message) { messenger.info(sender, message); }
                            });
                            messenger.success(sender, "Ready: " + provider.id() + " " + version + ".");
                        }
                    }
                } catch (IOException e) {
                    messenger.error(sender, "Runtime operation failed: " + e.getMessage());
                } finally {
                    coordinator.endRuntimeOperation();
                }
            }
        });
        if (!queued) coordinator.endRuntimeOperation();
    }

    private List<Provider> parseProviders(CommandSender sender, String value) {
        if ("all".equalsIgnoreCase(value)) return Arrays.asList(Provider.values());
        Provider provider = requireProvider(sender, value);
        return provider == null ? null : Collections.singletonList(provider);
    }

    private Provider requireProvider(CommandSender sender, String value) {
        Provider provider = Provider.parse(value);
        if (provider == null) messenger.error(sender, "Provider must be codex or claude.");
        return provider;
    }

    private SessionMode requireMode(CommandSender sender, String value) {
        SessionMode mode = SessionMode.parse(value);
        if (mode == null) messenger.error(sender, "Mode must be chat or agent.");
        return mode;
    }

    private void shortHelp(CommandSender sender) {
        messenger.info(sender, "Use /codex <message> to continue your current Codex agent.");
        messenger.info(sender, "Use /codex threads to open this thread list, or /codex thread new to start fresh.");
        listThreads(sender, Provider.CODEX, SessionMode.AGENT);
    }

    private void fullHelp(CommandSender sender) {
        messenger.info(sender, "SpigotLLM command reference (optional values are in brackets):");
        messenger.info(sender, "/sllm - compact help and clickable Codex thread list");
        messenger.info(sender, "/codex <prompt>");
        messenger.info(sender, "/codex threads | /codex thread <current|list|new>");
        messenger.info(sender, "/codex thread <switch|use> <id>");
        messenger.info(sender, "/codex thread rename [old-id] <new-name>");
        messenger.info(sender, "/codex thread delete <id> confirm");
        messenger.info(sender, "/codex <effort|level> [default|minimal|low|medium|high|xhigh]");
        messenger.info(sender, "/claude [chat|agent] <prompt>");
        messenger.info(sender, "/claude threads [chat|agent] | /claude thread current [chat|agent]");
        messenger.info(sender, "/claude thread new [chat|agent] <name>");
        messenger.info(sender, "/claude thread <switch|use> [chat|agent] <name>");
        messenger.info(sender, "/claude thread rename [chat|agent] [old-name] <new-name>");
        messenger.info(sender, "/claude thread delete [chat|agent] <name> confirm");
        messenger.info(sender, "/claude <effort|level> [chat|agent] [default|low|medium|high|xhigh|max|ultracode]");
        messenger.info(sender, "/sllm prompt <codex|claude> <chat|agent> <prompt>");
        messenger.info(sender, "/sllm threads [codex|claude] [chat|agent]");
        messenger.info(sender, "/sllm thread <codex|claude> <current|list|new|switch|use|rename|delete> ...");
        messenger.info(sender, "/sllm <effort|level> <codex|claude> <chat|agent> [level|default]");
        messenger.info(sender, "/sllm account <connect|status> <codex|claude>");
        messenger.info(sender, "/sllm account disconnect <codex|claude> confirm");
        messenger.info(sender, "/sllm account code claude [code] | /sllm account code cancel");
        messenger.info(sender, "/sllm runtime status [codex|claude|all]");
        messenger.info(sender, "/sllm runtime <install|update> <codex|claude|all>");
        messenger.info(sender, "/sllm runtime rollback <codex|claude|all> confirm");
        messenger.info(sender, "/sllm tools list | /sllm tools <inspect|enable|disable|remove> <owner> <resource-id>");
        messenger.info(sender, "/sllm tools purge <transient|all> confirm (console only)");
        messenger.info(sender, "/sllm cancel [codex|claude|all] | /sllm more [page]");
        messenger.info(sender, "/sllm access <list|add|remove> [player|uuid] (console only)");
        messenger.info(sender, "/sllm session <new|list|use|rename|delete> ... (legacy alias)");
    }

    private String join(String[] args, int start) {
        StringBuilder output = new StringBuilder();
        for (int index = start; index < args.length; index++) {
            if (output.length() > 0) output.append(' ');
            output.append(args[index]);
        }
        return output.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if ("codex".equalsIgnoreCase(command.getName()) || "claude".equalsIgnoreCase(command.getName())) {
            Provider directProvider = Provider.parse(command.getName());
            if (args.length == 1) {
                return directProvider == Provider.CODEX
                        ? matching(args[0], "thread", "threads", "effort")
                        : matching(args[0], "chat", "agent", "thread", "threads", "effort");
            }
            if ("threads".equalsIgnoreCase(args[0])) {
                return args.length == 2 && directProvider == Provider.CLAUDE
                        ? matching(args[1], "chat", "agent") : Collections.<String>emptyList();
            }
            if ("thread".equalsIgnoreCase(args[0])) {
                if (args.length == 2) {
                    return matching(args[1], "current", "list", "new", "switch", "rename", "delete");
                }
                String action = args[1].toLowerCase(Locale.ROOT);
                if (args.length == 3) {
                    if (directProvider == Provider.CODEX) {
                        if ("new".equals(action) || "current".equals(action) || "list".equals(action)) {
                            return Collections.emptyList();
                        }
                        return threadNames(sender, directProvider, SessionMode.AGENT, args[2]);
                    }
                    if ("current".equals(action) || "list".equals(action) || "new".equals(action)) {
                        return matching(args[2], "chat", "agent");
                    }
                    return matchingWithThreadNames(sender, directProvider, SessionMode.CHAT, args[2], "chat", "agent");
                }
                SessionMode selectedMode = SessionMode.parse(args[2]);
                if (args.length == 4 && selectedMode != null
                        && ("switch".equals(action) || "rename".equals(action) || "delete".equals(action))) {
                    return threadNames(sender, directProvider, selectedMode, args[3]);
                }
                if ("delete".equals(action)) {
                    boolean withMode = selectedMode != null;
                    if ((!withMode && args.length == 4) || (withMode && args.length == 5)) {
                        return matching(args[args.length - 1], "confirm");
                    }
                }
                return Collections.emptyList();
            }
            if ("effort".equalsIgnoreCase(args[0]) || "level".equalsIgnoreCase(args[0])) {
                if (args.length == 2) {
                    if (directProvider == Provider.CODEX) {
                        return matching(args[1], "default", "minimal", "low", "medium",
                                "high", "xhigh");
                    }
                    return matching(args[1], "chat", "agent", "default", "low", "medium", "high",
                            "xhigh", "max", "ultracode");
                }
                if (args.length == 3 && SessionMode.parse(args[1]) != null) {
                    if (directProvider == Provider.CODEX) {
                        return matching(args[2], "default", "minimal", "low", "medium", "high", "xhigh");
                    }
                    return matching(args[2], "default", "low", "medium", "high", "xhigh", "max", "ultracode");
                }
                return Collections.emptyList();
            }
            return Collections.emptyList();
        }
        if (args.length == 1) return matching(args[0], "help", "prompt", "account", "thread", "threads", "effort", "runtime", "tools", "cancel", "more", "access");
        if (args.length == 2 && "access".equalsIgnoreCase(args[0]) && sender instanceof ConsoleCommandSender) {
            return matching(args[1], "add", "remove", "list");
        }
        if (args.length == 2 && "account".equalsIgnoreCase(args[0])) return matching(args[1], "connect", "code", "status", "disconnect");
        if (args.length == 2 && "session".equalsIgnoreCase(args[0])) return matching(args[1], "new", "list", "use", "rename", "delete");
        if (args.length == 2 && "thread".equalsIgnoreCase(args[0])) return matching(args[1], "codex", "claude");
        if (args.length == 2 && "threads".equalsIgnoreCase(args[0])) return matching(args[1], "codex", "claude", "chat", "agent");
        if (args.length == 2 && ("effort".equalsIgnoreCase(args[0]) || "level".equalsIgnoreCase(args[0]))) {
            return matching(args[1], "codex", "claude");
        }
        if (args.length == 2 && "runtime".equalsIgnoreCase(args[0])) return matching(args[1], "install", "update", "rollback", "status");
        if (args.length == 2 && "tools".equalsIgnoreCase(args[0])) return matching(args[1], "list", "inspect", "enable", "disable", "remove", "purge");
        if (args.length == 3 && "tools".equalsIgnoreCase(args[0]) && "purge".equalsIgnoreCase(args[1])) {
            return matching(args[2], "transient", "all");
        }
        if (args.length == 4 && "tools".equalsIgnoreCase(args[0]) && "purge".equalsIgnoreCase(args[1])) {
            return matching(args[3], "confirm");
        }
        if (args.length == 3 && "account".equalsIgnoreCase(args[0])) {
            return matching(args[2], "codex", "claude");
        }
        if (args.length == 3 && "runtime".equalsIgnoreCase(args[0])) {
            return matching(args[2], "codex", "claude", "all");
        }
        if (args.length == 3 && "thread".equalsIgnoreCase(args[0])) {
            return matching(args[2], "current", "list", "new", "switch", "rename", "delete");
        }
        if (args.length == 3 && "threads".equalsIgnoreCase(args[0]) && Provider.parse(args[1]) != null) {
            return Provider.parse(args[1]) == Provider.CODEX
                    ? matching(args[2], "agent") : matching(args[2], "chat", "agent");
        }
        if (args.length == 3 && ("effort".equalsIgnoreCase(args[0]) || "level".equalsIgnoreCase(args[0]))) {
            return Provider.parse(args[1]) == Provider.CODEX
                    ? matching(args[2], "agent") : matching(args[2], "chat", "agent");
        }
        if (args.length == 4 && ("effort".equalsIgnoreCase(args[0]) || "level".equalsIgnoreCase(args[0]))) {
            Provider provider = Provider.parse(args[1]);
            if (provider == Provider.CODEX) {
                return matching(args[3], "default", "minimal", "low", "medium", "high", "xhigh");
            }
            if (provider == Provider.CLAUDE) {
                return matching(args[3], "default", "low", "medium", "high", "xhigh", "max", "ultracode");
            }
        }
        return Collections.emptyList();
    }

    private List<String> matching(String prefix, String... values) {
        List<String> result = new ArrayList<String>();
        for (String value : values) if (value.startsWith(prefix.toLowerCase(Locale.ROOT))) result.add(value);
        return result;
    }

    private List<String> matchingWithThreadNames(CommandSender sender, Provider provider, SessionMode mode,
                                                  String prefix, String... fixedValues) {
        List<String> result = matching(prefix, fixedValues);
        for (String name : threadNames(sender, provider, mode, prefix)) {
            if (!result.contains(name)) result.add(name);
        }
        return result;
    }

    private List<String> threadNames(CommandSender sender, Provider provider, SessionMode mode, String prefix) {
        if (provider == null) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        try {
            for (SessionRecord record : sessions.list(Identity.from(sender))) {
                if (record.getProvider() == provider && record.getMode() == mode
                        && record.getName().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    result.add(record.getName());
                }
            }
        } catch (IOException ignored) { }
        return result;
    }

    private static final class AccessTarget {
        private final UUID uuid;
        private final String name;

        private AccessTarget(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }
}
