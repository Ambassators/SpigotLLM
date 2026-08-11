package dev.foreground.spigotllm.agent;

import dev.foreground.spigotllm.access.AccessStore;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Registers real, removable Bukkit commands without depending on CraftBukkit classes. */
public final class DynamicCommandManager {
    private static final Pattern LABEL = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    private final CommandMap commandMap;
    private final AccessStore accessStore;

    public DynamicCommandManager(Server server, AccessStore accessStore) {
        this(resolveCommandMap(server), accessStore);
    }

    DynamicCommandManager(CommandMap commandMap, AccessStore accessStore) {
        if (commandMap == null || accessStore == null) throw new IllegalArgumentException("Command map and access store are required.");
        this.commandMap = commandMap;
        this.accessStore = accessStore;
    }

    public Registration register(Spec spec, Handler handler, Completion completion) {
        if (spec == null || handler == null) throw new IllegalArgumentException("Command spec and handler are required.");
        if (spec.accessMode == AccessMode.PERMISSION
                && (spec.permission == null || spec.permission.trim().isEmpty())) {
            throw new IllegalArgumentException("Permission access requires a Bukkit permission node.");
        }
        String name = normalize(spec.name);
        List<String> aliases = new ArrayList<String>();
        for (String alias : spec.aliases) aliases.add(normalize(alias));
        ensureFree(name);
        for (String alias : aliases) ensureFree(alias);
        RuntimeCommand command = new RuntimeCommand(name, spec, aliases, handler, completion);
        if (!commandMap.register(name, "spigotllm-agent", command)) {
            command.unregister(commandMap);
            Map<String, Command> known = knownCommands(commandMap);
            if (known != null) {
                Iterator<Map.Entry<String, Command>> iterator = known.entrySet().iterator();
                while (iterator.hasNext()) if (iterator.next().getValue() == command) iterator.remove();
            }
            throw new IllegalArgumentException("Command label is already registered: " + name);
        }
        return new Registration(command);
    }

    private void ensureFree(String name) {
        if (commandMap.getCommand(name) != null) throw new IllegalArgumentException("Command label is already registered: " + name);
    }

    private String normalize(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (!LABEL.matcher(clean).matches()) {
            throw new IllegalArgumentException("Command labels must match " + LABEL.pattern() + ".");
        }
        return clean;
    }

    private boolean allowed(AccessMode mode, String permission, CommandSender sender) {
        AccessMode effective = mode == null ? AccessMode.AUTHORIZED : mode;
        if (effective == AccessMode.EVERYONE) return true;
        if (effective == AccessMode.CONSOLE) return sender instanceof ConsoleCommandSender;
        if (effective == AccessMode.PLAYERS) return sender instanceof Player;
        if (effective == AccessMode.OPS) return sender.isOp();
        if (effective == AccessMode.PERMISSION) return permission != null && !permission.isEmpty() && sender.hasPermission(permission);
        if (sender instanceof ConsoleCommandSender) return true;
        return sender instanceof Player && sender.isOp()
                && accessStore.isAuthorized(((Player) sender).getUniqueId());
    }

    private static CommandMap resolveCommandMap(Server server) {
        Class<?> type = server.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod("getCommandMap");
                method.setAccessible(true);
                Object value = method.invoke(server);
                if (value instanceof CommandMap) return (CommandMap) value;
            } catch (ReflectiveOperationException ignored) { }
            type = type.getSuperclass();
        }
        throw new IllegalStateException("This server does not expose a compatible Bukkit CommandMap.");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(CommandMap map) {
        Class<?> type = map.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                Object value = field.get(map);
                if (value instanceof Map) return (Map<String, Command>) value;
            } catch (ReflectiveOperationException ignored) { }
            type = type.getSuperclass();
        }
        return null;
    }

    public enum AccessMode {
        AUTHORIZED, CONSOLE, PLAYERS, OPS, PERMISSION, EVERYONE;

        public static AccessMode parse(String value) {
            if (value == null || value.trim().isEmpty()) return AUTHORIZED;
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            if ("authorized".equals(normalized) || "authorized-operators".equals(normalized)) return AUTHORIZED;
            if ("console".equals(normalized)) return CONSOLE;
            if ("players".equals(normalized) || "player".equals(normalized)) return PLAYERS;
            if ("ops".equals(normalized) || "op".equals(normalized)) return OPS;
            if ("permission".equals(normalized)) return PERMISSION;
            if ("everyone".equals(normalized) || "any".equals(normalized)) return EVERYONE;
            throw new IllegalArgumentException("Unknown command access mode: " + value);
        }
    }

    public static final class Spec {
        public final String name;
        public final List<String> aliases;
        public final String description;
        public final String usage;
        public final String permission;
        public final String permissionMessage;
        public final AccessMode accessMode;

        public Spec(String name, List<String> aliases, String description, String usage,
                    String permission, String permissionMessage, AccessMode accessMode) {
            this.name = name;
            this.aliases = aliases == null ? Collections.<String>emptyList() : aliases;
            this.description = description == null ? "Temporary SpigotLLM agent command" : description;
            this.usage = usage == null ? "/" + name : usage;
            this.permission = permission;
            this.permissionMessage = permissionMessage == null ? "You cannot use this command." : permissionMessage;
            this.accessMode = accessMode == null ? AccessMode.AUTHORIZED : accessMode;
        }
    }

    public interface Handler {
        boolean execute(CommandSender sender, String label, String[] arguments) throws Exception;
    }

    public interface Completion {
        List<String> complete(CommandSender sender, String alias, String[] arguments) throws Exception;
    }

    public final class Registration implements AutoCloseable {
        private final RuntimeCommand command;
        private boolean closed;

        Registration(RuntimeCommand command) {
            this.command = command;
        }

        public String name() {
            return command.getName();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            command.unregister(commandMap);
            Map<String, Command> known = knownCommands(commandMap);
            if (known != null) {
                Iterator<Map.Entry<String, Command>> iterator = known.entrySet().iterator();
                while (iterator.hasNext()) if (iterator.next().getValue() == command) iterator.remove();
            }
        }
    }

    private final class RuntimeCommand extends Command {
        private final Handler handler;
        private final Completion completion;
        private final AccessMode accessMode;

        RuntimeCommand(String name, Spec spec, List<String> aliases, Handler handler, Completion completion) {
            super(name, spec.description, spec.usage, aliases);
            this.handler = handler;
            this.completion = completion;
            this.accessMode = spec.accessMode;
            if (spec.permission != null && !spec.permission.isEmpty()) setPermission(spec.permission);
            setPermissionMessage(spec.permissionMessage);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            if (!allowed(accessMode, getPermission(), sender)) {
                sender.sendMessage(getPermissionMessage());
                return true;
            }
            try {
                return handler.execute(sender, commandLabel, args);
            } catch (Exception e) {
                throw new org.bukkit.command.CommandException("Temporary command callback failed; see its agent event stream.");
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!allowed(accessMode, getPermission(), sender) || completion == null) return Collections.emptyList();
            try {
                List<String> result = completion.complete(sender, alias, args);
                return result == null ? Collections.<String>emptyList() : result;
            } catch (Exception failure) {
                throw new org.bukkit.command.CommandException(
                        "Temporary command tab completion failed; see its agent event stream.");
            }
        }
    }
}
