package dev.foreground.spigotllm.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.foreground.spigotllm.access.AccessStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentRuntimeUtilityTest {
    @TempDir Path temporary;

    @Test
    void writesEventsAndSearchesBoundedLogTail() throws Exception {
        Path events = temporary.resolve("lease/events.jsonl");
        AgentEventStream stream = new AgentEventStream(Logger.getLogger("test"), 100, 64 * 1024L);
        JsonObject first = new JsonObject();
        first.addProperty("type", "test.event");
        first.addProperty("value", 42);
        stream.emit(events, first);
        stream.close();

        String written = new String(Files.readAllBytes(events), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"type\":\"test.event\""));
        assertTrue(written.contains("\"timestamp\""));

        Path log = temporary.resolve("latest.log");
        Files.write(log, ("one\nERROR first\ntwo\nERROR second\n").getBytes(StandardCharsets.UTF_8));
        List<String> matches = BukkitDiagnostics.tailMatches(log, "error", 1, 4096);
        assertEquals(1, matches.size());
        assertEquals("ERROR second", matches.get(0));
    }

    @Test
    void resolvesPropertiesFiltersAndCommandAccessAliases() {
        Child value = new Child();
        assertEquals("secret", StructuredEventWatch.property(value, "parent.value"));
        assertTrue(new StructuredEventWatch.Filter("x", "contains", new JsonPrimitive("ecr")).matches("secret"));
        assertFalse(new StructuredEventWatch.Filter("x", "equals", new JsonPrimitive("other")).matches("secret"));
        assertEquals(DynamicCommandManager.AccessMode.AUTHORIZED,
                DynamicCommandManager.AccessMode.parse("authorized-operators"));
        assertEquals(DynamicCommandManager.AccessMode.EVERYONE,
                DynamicCommandManager.AccessMode.parse("any"));
    }

    @Test
    void deepMiniReflectionTraversesPrivateInheritedMembers() throws Exception {
        DeepMiniReflection reflection = new DeepMiniReflection(getClass().getClassLoader());
        Child target = new Child();
        assertEquals("secret", reflection.get(target.parent, "value"));
        reflection.set(target.parent, "value", "changed");
        assertEquals("changed", reflection.invoke(target.parent, "read", new Class<?>[0]));
        Object created = reflection.construct(Parent.class, new Class<?>[] { String.class }, "constructed");
        assertEquals("constructed", reflection.get(created, "value"));
    }

    @Test
    void registersPrimaryCommandLabelWithStableFallbackPrefix() {
        RecordingCommandMap map = new RecordingCommandMap();
        DynamicCommandManager manager = new DynamicCommandManager(map,
                new AccessStore(temporary.resolve("access.json")));
        DynamicCommandManager.Registration registration = manager.register(
                new DynamicCommandManager.Spec("hello", java.util.Collections.singletonList("hi"),
                        null, null, null, null, DynamicCommandManager.AccessMode.EVERYONE),
                (sender, label, arguments) -> true, null);

        assertEquals("hello", map.label);
        assertEquals("spigotllm-agent", map.fallbackPrefix);
        assertTrue(map.knownCommands.containsKey("hello"));
        registration.close();
        assertTrue(map.knownCommands.isEmpty());
    }

    private static final class Child {
        private final Parent parent = new Parent("secret");
    }

    private static class Parent {
        private String value;
        private Parent(String value) { this.value = value; }
        private String read() { return value; }
    }

    private static final class RecordingCommandMap implements CommandMap {
        private final Map<String, Command> knownCommands = new LinkedHashMap<String, Command>();
        private String label;
        private String fallbackPrefix;

        @Override public void registerAll(String fallbackPrefix, List<Command> commands) {
            for (Command command : commands) register(command.getName(), fallbackPrefix, command);
        }
        @Override public boolean register(String fallbackPrefix, Command command) {
            return register(command.getName(), fallbackPrefix, command);
        }
        @Override public boolean register(String label, String fallbackPrefix, Command command) {
            this.label = label;
            this.fallbackPrefix = fallbackPrefix;
            knownCommands.put(label, command);
            for (String alias : command.getAliases()) knownCommands.put(alias, command);
            command.register(this);
            return true;
        }
        @Override public boolean dispatch(CommandSender sender, String commandLine) { return false; }
        @Override public void clearCommands() { knownCommands.clear(); }
        @Override public Command getCommand(String name) { return knownCommands.get(name); }
        @Override public List<String> tabComplete(CommandSender sender, String commandLine) { return new ArrayList<String>(); }
    }
}
