package dev.foreground.spigotllm.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.foreground.spigotllm.agent.code.MiniCommandAccess;
import dev.foreground.spigotllm.agent.code.MiniCommandHandler;
import dev.foreground.spigotllm.agent.code.MiniContext;
import dev.foreground.spigotllm.agent.code.MiniEventHandler;
import dev.foreground.spigotllm.agent.code.MiniReflection;
import dev.foreground.spigotllm.agent.code.MiniRegistration;
import dev.foreground.spigotllm.agent.code.MiniTabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/** Tracked Bukkit host implementation used by snippets and generated mini-modules. */
public final class BukkitMiniContext implements MiniContext, AutoCloseable {
    private final JavaPlugin plugin;
    private final String owner;
    private final String resourceId;
    private final DynamicCommandManager commands;
    private final MiniReflection reflection;
    private final HandleAccess handles;
    private final EventSink events;
    private final FailureHandler failureHandler;
    private final long slowMillis;
    private final int maxChildren;
    private final List<MiniRegistration> registrations = new CopyOnWriteArrayList<MiniRegistration>();
    private final List<BukkitTask> tasks = new CopyOnWriteArrayList<BukkitTask>();
    private volatile boolean active = true;

    public BukkitMiniContext(JavaPlugin plugin, String owner, String resourceId,
                             DynamicCommandManager commands, MiniReflection reflection,
                             HandleAccess handles, EventSink events,
                             FailureHandler failureHandler, long slowMillis, int maxChildren) {
        this.plugin = plugin;
        this.owner = owner;
        this.resourceId = resourceId;
        this.commands = commands;
        this.reflection = reflection;
        this.handles = handles;
        this.events = events;
        this.failureHandler = failureHandler;
        this.slowMillis = Math.max(1L, slowMillis);
        this.maxChildren = Math.max(1, maxChildren);
    }

    @Override public Server server() { return plugin.getServer(); }
    @Override public String owner() { return owner; }
    @Override public String resourceId() { return resourceId; }

    @Override
    public Object emit(Object value) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "code.emit");
        event.addProperty("resourceId", resourceId);
        event.add("value", handles.encode(value));
        events.emit(event);
        return value;
    }

    @Override public void log(Level level, String message) { log(level, message, null); }

    @Override
    public void log(Level level, String message, Throwable error) {
        Level effective = level == null ? Level.INFO : level;
        String clean = "Agent module " + resourceId + ": " + (message == null ? "" : message);
        if (error == null) plugin.getLogger().log(effective, clean);
        else plugin.getLogger().log(effective, clean, error);
    }

    @Override
    public <T extends Event> MiniRegistration listen(final Class<T> eventType, EventPriority priority,
                                                      boolean ignoreCancelled, final MiniEventHandler<T> handler) {
        requireActive();
        requireCapacity();
        if (eventType == null || handler == null) throw new IllegalArgumentException("Event type and handler are required.");
        final Listener listener = new Listener() { };
        final TrackedRegistration registration = new TrackedRegistration("event-" + shortId(), new Runnable() {
            @Override public void run() { HandlerList.unregisterAll(listener); }
        });
        plugin.getServer().getPluginManager().registerEvent(eventType, listener,
                priority == null ? EventPriority.NORMAL : priority, new EventExecutor() {
                    @Override public void execute(Listener ignored, Event event) throws EventException {
                        if (!registration.isActive()) return;
                        long started = System.nanoTime();
                        try {
                            handler.handle(eventType.cast(event));
                        } catch (Throwable error) {
                            fail(error);
                        } finally {
                            warnSlow("event " + eventType.getName(), started);
                        }
                    }
                }, plugin, ignoreCancelled);
        registrations.add(registration);
        return registration;
    }

    @Override
    public MiniRegistration command(String label, List<String> aliases, String usage,
                                    MiniCommandAccess access, String permission,
                                    final MiniCommandHandler handler, final MiniTabCompleter tabCompleter) {
        requireActive();
        requireCapacity();
        DynamicCommandManager.Spec spec = new DynamicCommandManager.Spec(label, aliases,
                "Temporary command created by a SpigotLLM agent", usage, permission,
                "You cannot use this temporary command.", access(access));
        final DynamicCommandManager.Registration command = commands.register(spec,
                new DynamicCommandManager.Handler() {
                    @Override public boolean execute(CommandSender sender, String commandLabel, String[] arguments) throws Exception {
                        long started = System.nanoTime();
                        try { return handler.execute(sender, commandLabel, arguments); }
                        catch (Throwable error) { fail(error); throw error instanceof Exception ? (Exception) error : new Exception(error); }
                        finally { warnSlow("command", started); }
                    }
                }, tabCompleter == null ? null : new DynamicCommandManager.Completion() {
                    @Override public List<String> complete(CommandSender sender, String alias, String[] arguments) throws Exception {
                        try { return tabCompleter.complete(sender, alias, arguments); }
                        catch (Throwable error) {
                            fail(error);
                            throw error instanceof Exception ? (Exception) error : new Exception(error);
                        }
                    }
                });
        TrackedRegistration registration = new TrackedRegistration("command-" + shortId(), new Runnable() {
            @Override public void run() { command.close(); }
        });
        registrations.add(registration);
        return registration;
    }

    @Override
    public BukkitTask runSync(BukkitRunnable task) {
        requireActive();
        requireCapacity();
        return track(wrap(task, "scheduled task").runTask(plugin));
    }

    @Override
    public BukkitTask runLater(BukkitRunnable task, long delayTicks) {
        requireActive();
        requireCapacity();
        return track(wrap(task, "delayed task").runTaskLater(plugin, Math.max(0L, delayTicks)));
    }

    @Override
    public BukkitTask runTimer(BukkitRunnable task, long delayTicks, long periodTicks) {
        requireActive();
        requireCapacity();
        if (periodTicks < 1L) throw new IllegalArgumentException("periodTicks must be at least 1.");
        return track(wrap(task, "repeating task").runTaskTimer(plugin, Math.max(0L, delayTicks), periodTicks));
    }

    @Override public MiniReflection reflection() { return reflection; }
    @Override public String retain(Object value) { return handles.retain(value); }
    @Override public Object resolve(String handle) { return handles.resolve(handle); }
    @Override public boolean release(String handle) { return handles.release(handle); }

    @Override
    public boolean dispatchCommand(CommandSender sender, String commandLine) {
        String clean = commandLine == null ? "" : commandLine.trim();
        while (clean.startsWith("/")) clean = clean.substring(1).trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("Command cannot be empty.");
        return plugin.getServer().dispatchCommand(sender == null ? plugin.getServer().getConsoleSender() : sender, clean);
    }

    @Override
    public void sendMessage(CommandSender recipient, String message) {
        if (recipient == null) throw new IllegalArgumentException("Recipient is required.");
        recipient.sendMessage(message == null ? "" : message);
    }

    private BukkitTask track(BukkitTask task) {
        tasks.add(task);
        return task;
    }

    private BukkitRunnable wrap(final BukkitRunnable delegate, final String operation) {
        if (delegate == null) throw new IllegalArgumentException("BukkitRunnable is required.");
        return new BukkitRunnable() {
            @Override public void run() {
                long started = System.nanoTime();
                try { delegate.run(); }
                catch (Throwable error) { fail(error); }
                finally { warnSlow(operation, started); }
            }
        };
    }

    private DynamicCommandManager.AccessMode access(MiniCommandAccess value) {
        if (value == null || value == MiniCommandAccess.AUTHORIZED_OPERATORS) return DynamicCommandManager.AccessMode.AUTHORIZED;
        if (value == MiniCommandAccess.CONSOLE) return DynamicCommandManager.AccessMode.CONSOLE;
        if (value == MiniCommandAccess.PLAYERS) return DynamicCommandManager.AccessMode.PLAYERS;
        if (value == MiniCommandAccess.OPS) return DynamicCommandManager.AccessMode.OPS;
        if (value == MiniCommandAccess.PERMISSION) return DynamicCommandManager.AccessMode.PERMISSION;
        return DynamicCommandManager.AccessMode.EVERYONE;
    }

    private void fail(Throwable error) {
        JsonObject event = new JsonObject();
        event.addProperty("type", "code.error");
        event.addProperty("resourceId", resourceId);
        event.addProperty("error", error.getClass().getName());
        event.addProperty("message", safe(error.getMessage()));
        events.emit(event);
        if (failureHandler != null) failureHandler.failed(error);
    }

    private void warnSlow(String operation, long startedNanos) {
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsed >= slowMillis) {
            plugin.getLogger().warning("Agent runtime " + operation + " for " + resourceId
                    + " blocked the server thread for " + elapsed + " ms.");
            JsonObject event = new JsonObject();
            event.addProperty("type", "code.slow");
            event.addProperty("resourceId", resourceId);
            event.addProperty("operation", operation);
            event.addProperty("elapsedMillis", elapsed);
            events.emit(event);
        }
    }

    private void requireActive() {
        if (!active) throw new IllegalStateException("Mini-module context is closed.");
    }

    private void requireCapacity() {
        if (registrations.size() + tasks.size() >= maxChildren) {
            throw new IllegalStateException("This mini-module reached its tracked child-resource limit of "
                    + maxChildren + ".");
        }
    }

    @Override
    public void close() {
        if (!active) return;
        active = false;
        List<MiniRegistration> copy = new ArrayList<MiniRegistration>(registrations);
        Collections.reverse(copy);
        for (MiniRegistration registration : copy) {
            try { registration.close(); } catch (RuntimeException ignored) { }
        }
        registrations.clear();
        for (BukkitTask task : tasks) {
            try { task.cancel(); } catch (RuntimeException ignored) { }
        }
        tasks.clear();
    }

    private String shortId() { return UUID.randomUUID().toString().substring(0, 8); }
    private String safe(String value) { return value == null ? "Unknown error" : value.replace('\r', ' ').replace('\n', ' '); }

    public interface HandleAccess {
        String retain(Object value);
        Object resolve(String handle);
        boolean release(String handle);
        JsonElement encode(Object value);
    }

    public interface EventSink { void emit(JsonObject event); }
    public interface FailureHandler { void failed(Throwable error); }

    private final class TrackedRegistration implements MiniRegistration {
        private final String id;
        private final Runnable cleanup;
        private volatile boolean registrationActive = true;

        TrackedRegistration(String id, Runnable cleanup) {
            this.id = id;
            this.cleanup = cleanup;
        }

        @Override public String id() { return id; }
        @Override public boolean isActive() { return registrationActive && active; }

        @Override
        public void close() {
            if (!registrationActive) return;
            registrationActive = false;
            cleanup.run();
            registrations.remove(this);
        }
    }
}
