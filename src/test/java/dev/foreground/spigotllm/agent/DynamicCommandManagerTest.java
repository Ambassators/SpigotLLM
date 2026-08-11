package dev.foreground.spigotllm.agent;

import dev.foreground.spigotllm.access.AccessStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DynamicCommandManagerTest {
    @TempDir Path temporary;

    @Test
    void rejectsPrimaryAndAliasCollisionsWithoutChangingExistingCommands() {
        RecordingCommandMap map = new RecordingCommandMap();
        Command existing = inert("existing");
        map.knownCommands.put("taken", existing);
        DynamicCommandManager manager = manager(map);

        assertThrows(IllegalArgumentException.class, () -> manager.register(
                spec("taken", Collections.<String>emptyList(), DynamicCommandManager.AccessMode.EVERYONE, null),
                successfulHandler(), null));
        assertThrows(IllegalArgumentException.class, () -> manager.register(
                spec("fresh", Collections.singletonList("taken"), DynamicCommandManager.AccessMode.EVERYONE, null),
                successfulHandler(), null));

        assertEquals(1, map.knownCommands.size());
        assertSame(existing, map.knownCommands.get("taken"));
    }

    @Test
    void cleansAllRuntimeLabelsWhenRegistrationFailsOrCloses() {
        RecordingCommandMap failedMap = new RecordingCommandMap();
        failedMap.failRegistration = true;
        DynamicCommandManager failedManager = manager(failedMap);
        assertThrows(IllegalArgumentException.class, () -> failedManager.register(
                spec("failed", Collections.singletonList("failedalias"),
                        DynamicCommandManager.AccessMode.EVERYONE, null), successfulHandler(), null));
        assertTrue(failedMap.knownCommands.isEmpty(), "failed registration must not leak fallback labels");

        RecordingCommandMap map = new RecordingCommandMap();
        Command unrelated = inert("unrelated");
        map.knownCommands.put("unrelated", unrelated);
        DynamicCommandManager.Registration registration = manager(map).register(
                spec("temporary", Arrays.asList("tmp", "t"), DynamicCommandManager.AccessMode.EVERYONE, null),
                successfulHandler(), null);
        Command runtime = map.getCommand("temporary");
        map.knownCommands.put("spigotllm-agent:temporary", runtime);
        map.knownCommands.put("spigotllm-agent:tmp", runtime);

        registration.close();
        registration.close();

        assertEquals(Collections.singletonMap("unrelated", unrelated), map.knownCommands);
    }

    @Test
    void enforcesConsolePlayerOpAndPermissionPolicies() {
        RecordingCommandMap map = new RecordingCommandMap();
        DynamicCommandManager manager = manager(map);
        AtomicInteger calls = new AtomicInteger();
        DynamicCommandManager.Handler handler = (sender, label, arguments) -> {
            calls.incrementAndGet();
            return true;
        };
        SenderState console = sender(ConsoleCommandSender.class, null, false, false);
        SenderState ordinaryPlayer = sender(Player.class, UUID.randomUUID(), false, false);
        SenderState opPlayer = sender(Player.class, UUID.randomUUID(), true, false);
        SenderState permittedPlayer = sender(Player.class, UUID.randomUUID(), false, true);

        Command consoleOnly = register(manager, map, "consoleonly", DynamicCommandManager.AccessMode.CONSOLE, null, handler);
        assertTrue(consoleOnly.execute(console.sender, "consoleonly", new String[0]));
        assertTrue(consoleOnly.execute(ordinaryPlayer.sender, "consoleonly", new String[0]));
        assertEquals(1, calls.get());
        assertEquals(Collections.singletonList("denied"), ordinaryPlayer.messages);

        Command players = register(manager, map, "playersonly", DynamicCommandManager.AccessMode.PLAYERS, null, handler);
        players.execute(console.sender, "playersonly", new String[0]);
        players.execute(ordinaryPlayer.sender, "playersonly", new String[0]);
        assertEquals(2, calls.get());

        Command ops = register(manager, map, "opsonly", DynamicCommandManager.AccessMode.OPS, null, handler);
        ops.execute(ordinaryPlayer.sender, "opsonly", new String[0]);
        ops.execute(opPlayer.sender, "opsonly", new String[0]);
        assertEquals(3, calls.get());

        Command permission = register(manager, map, "permitted", DynamicCommandManager.AccessMode.PERMISSION,
                "runtime.use", handler);
        permission.execute(ordinaryPlayer.sender, "permitted", new String[0]);
        permission.execute(permittedPlayer.sender, "permitted", new String[0]);
        assertEquals(4, calls.get());
    }

    @Test
    void authorizedPolicyRequiresAllowlistedOperatorButAlwaysAllowsConsole() throws Exception {
        RecordingCommandMap map = new RecordingCommandMap();
        AccessStore access = new AccessStore(temporary.resolve("access.json"));
        UUID authorizedId = UUID.randomUUID();
        access.add(authorizedId, "Authorized");
        DynamicCommandManager manager = new DynamicCommandManager(map, access);
        AtomicInteger calls = new AtomicInteger();
        DynamicCommandManager.Handler handler = (sender, label, arguments) -> {
            calls.incrementAndGet();
            return true;
        };
        Command command = register(manager, map, "authorized", DynamicCommandManager.AccessMode.AUTHORIZED, null, handler);

        SenderState console = sender(ConsoleCommandSender.class, null, false, false);
        SenderState authorizedOp = sender(Player.class, authorizedId, true, false);
        SenderState authorizedNonOp = sender(Player.class, authorizedId, false, false);
        SenderState unauthorizedOp = sender(Player.class, UUID.randomUUID(), true, false);
        command.execute(console.sender, "authorized", new String[0]);
        command.execute(authorizedOp.sender, "authorized", new String[0]);
        command.execute(authorizedNonOp.sender, "authorized", new String[0]);
        command.execute(unauthorizedOp.sender, "authorized", new String[0]);

        assertEquals(2, calls.get());
        assertEquals(Collections.singletonList("denied"), authorizedNonOp.messages);
        assertEquals(Collections.singletonList("denied"), unauthorizedOp.messages);
    }

    @Test
    void protectsTabCompletionAndPropagatesCallbackFailures() {
        RecordingCommandMap map = new RecordingCommandMap();
        DynamicCommandManager manager = manager(map);
        SenderState player = sender(Player.class, UUID.randomUUID(), false, false);
        SenderState console = sender(ConsoleCommandSender.class, null, false, false);
        AtomicInteger completions = new AtomicInteger();
        manager.register(spec("complete", Collections.<String>emptyList(),
                        DynamicCommandManager.AccessMode.PLAYERS, null), successfulHandler(),
                (sender, alias, arguments) -> {
                    completions.incrementAndGet();
                    return Arrays.asList("one", "two");
                });
        Command command = map.getCommand("complete");

        assertEquals(Arrays.asList("one", "two"), command.tabComplete(player.sender, "complete", new String[0]));
        assertTrue(command.tabComplete(console.sender, "complete", new String[0]).isEmpty());
        assertEquals(1, completions.get());

        manager.register(spec("explode", Collections.<String>emptyList(),
                        DynamicCommandManager.AccessMode.EVERYONE, null),
                (sender, label, arguments) -> { throw new Exception("boom"); },
                (sender, alias, arguments) -> { throw new Exception("completion boom"); });
        Command exploding = map.getCommand("explode");
        assertThrows(CommandException.class,
                () -> exploding.execute(player.sender, "explode", new String[0]));
        assertThrows(CommandException.class,
                () -> exploding.tabComplete(player.sender, "explode", new String[0]));
    }

    private DynamicCommandManager manager(RecordingCommandMap map) {
        return new DynamicCommandManager(map, new AccessStore(temporary.resolve("access.json")));
    }

    private static Command register(DynamicCommandManager manager, RecordingCommandMap map, String name,
                                    DynamicCommandManager.AccessMode mode, String permission,
                                    DynamicCommandManager.Handler handler) {
        manager.register(spec(name, Collections.<String>emptyList(), mode, permission), handler, null);
        return map.getCommand(name);
    }

    private static DynamicCommandManager.Spec spec(String name, List<String> aliases,
                                                   DynamicCommandManager.AccessMode mode, String permission) {
        return new DynamicCommandManager.Spec(name, aliases, "test", "/" + name,
                permission, "denied", mode);
    }

    private static DynamicCommandManager.Handler successfulHandler() {
        return (sender, label, arguments) -> true;
    }

    private static Command inert(String name) {
        return new Command(name) {
            @Override public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return true;
            }
        };
    }

    private static <T extends CommandSender> SenderState sender(Class<T> type, UUID uuid,
                                                                 boolean op, boolean permission) {
        SenderState state = new SenderState();
        state.uuid = uuid;
        state.op = op;
        state.permission = permission;
        state.sender = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, state));
        return state;
    }

    private static final class SenderState implements InvocationHandler {
        CommandSender sender;
        UUID uuid;
        boolean op;
        boolean permission;
        final List<String> messages = new ArrayList<String>();

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            if ("getUniqueId".equals(name)) return uuid;
            if ("isOp".equals(name)) return op;
            if ("hasPermission".equals(name)) return permission;
            if ("sendMessage".equals(name)) {
                if (arguments[0] instanceof String[]) messages.addAll(Arrays.asList((String[]) arguments[0]));
                else messages.add((String) arguments[0]);
                return null;
            }
            if ("getName".equals(name)) return uuid == null ? "Console" : "Player";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) return proxy == arguments[0];
            if ("toString".equals(name)) return "SenderProxy";
            Class<?> result = method.getReturnType();
            if (result == Boolean.TYPE) return false;
            if (result == Byte.TYPE) return (byte) 0;
            if (result == Short.TYPE) return (short) 0;
            if (result == Integer.TYPE) return 0;
            if (result == Long.TYPE) return 0L;
            if (result == Float.TYPE) return 0F;
            if (result == Double.TYPE) return 0D;
            if (result == Character.TYPE) return '\0';
            return null;
        }
    }

    private static final class RecordingCommandMap implements CommandMap {
        final Map<String, Command> knownCommands = new LinkedHashMap<String, Command>();
        boolean failRegistration;

        @Override public void registerAll(String fallbackPrefix, List<Command> commands) {
            for (Command command : commands) register(command.getName(), fallbackPrefix, command);
        }
        @Override public boolean register(String fallbackPrefix, Command command) {
            return register(command.getName(), fallbackPrefix, command);
        }
        @Override public boolean register(String label, String fallbackPrefix, Command command) {
            knownCommands.put(label, command);
            for (String alias : command.getAliases()) knownCommands.put(alias, command);
            if (failRegistration) {
                knownCommands.put(fallbackPrefix + ":" + label, command);
                return false;
            }
            command.register(this);
            return true;
        }
        @Override public boolean dispatch(CommandSender sender, String commandLine) { return false; }
        @Override public void clearCommands() { knownCommands.clear(); }
        @Override public Command getCommand(String name) { return knownCommands.get(name); }
        @Override public List<String> tabComplete(CommandSender sender, String commandLine) {
            return Collections.emptyList();
        }
    }
}
