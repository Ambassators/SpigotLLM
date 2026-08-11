package dev.foreground.spigotllm.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.foreground.spigotllm.agent.code.CompiledModule;
import dev.foreground.spigotllm.agent.code.CompiledSnippet;
import dev.foreground.spigotllm.agent.code.CompilationDiagnostic;
import dev.foreground.spigotllm.agent.code.CompilationException;
import dev.foreground.spigotllm.agent.code.MiniCommandAccess;
import dev.foreground.spigotllm.agent.code.MiniContext;
import dev.foreground.spigotllm.agent.code.MiniModule;
import dev.foreground.spigotllm.agent.code.MiniReflection;
import dev.foreground.spigotllm.agent.code.RuntimeCompiler;
import dev.foreground.spigotllm.agent.reflect.ReflectionException;
import dev.foreground.spigotllm.agent.reflect.ReflectionService;
import dev.foreground.spigotllm.agent.runtime.AgentProtocol;
import dev.foreground.spigotllm.agent.runtime.AgentRequest;
import dev.foreground.spigotllm.agent.runtime.Lifecycle;
import dev.foreground.spigotllm.agent.runtime.ManagedResource;
import dev.foreground.spigotllm.agent.runtime.RemovalReason;
import dev.foreground.spigotllm.agent.runtime.ResourceMetadata;
import dev.foreground.spigotllm.agent.runtime.ResourceRegistration;
import dev.foreground.spigotllm.agent.runtime.ResourceRegistry;
import dev.foreground.spigotllm.agent.runtime.ResourceRegistryException;
import dev.foreground.spigotllm.access.AccessStore;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Coordinates structured diagnostics, reflection, compiled code, and managed Bukkit resources. */
public final class AgentToolRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Path root;
    private final Path auditFile;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final AgentEventStream eventWriter;
    private final TickMonitor ticks = new TickMonitor(1200);
    private final BukkitDiagnostics diagnostics;
    private final DynamicCommandManager commands;
    private final ReflectionService reflection;
    private final MiniReflection miniReflection;
    private final ResourceRegistry resources;
    private final RuntimeCompiler compiler;
    private final ExecutorService compilerExecutor;
    private final long slowMainThreadMillis;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final boolean enabled;
    private final long maxTtlSeconds;
    private final int maxResourcesPerOwner;
    private final Map<String, ModuleResource> modules = new ConcurrentHashMap<String, ModuleResource>();

    public AgentToolRuntime(JavaPlugin plugin, AccessStore accessStore, Path root, Path consoleLog,
                            int maxResourcesPerOwner, int maxHandlesPerOwner,
                            int maxSourceBytes, int maxQueuedEvents, long maxEventStreamBytes,
                            int compilerThreads, long slowMainThreadMillis,
                            boolean loadPersistentModules, boolean enabled,
                            long maxTtlSeconds) throws IOException {
        this.plugin = plugin;
        this.root = root.toAbsolutePath().normalize();
        this.auditFile = this.root.resolve("audit.jsonl");
        Files.createDirectories(this.root);
        this.eventWriter = new AgentEventStream(plugin.getLogger(), maxQueuedEvents, maxEventStreamBytes);
        this.diagnostics = new BukkitDiagnostics(plugin.getServer(), consoleLog, ticks);
        this.commands = new DynamicCommandManager(plugin.getServer(), accessStore);
        this.reflection = new ReflectionService(new BukkitRootResolver(plugin), maxHandlesPerOwner);
        this.miniReflection = new DeepMiniReflection(plugin.getClass().getClassLoader());
        this.resources = new ResourceRegistry(maxResourcesPerOwner);
        this.compiler = new RuntimeCompiler(plugin.getClass().getClassLoader(), maxSourceBytes);
        this.slowMainThreadMillis = Math.max(1L, slowMainThreadMillis);
        this.enabled = enabled;
        this.maxResourcesPerOwner = Math.max(1, maxResourcesPerOwner);
        this.maxTtlSeconds = Math.max(1L, Math.min(86400L, maxTtlSeconds));
        this.compilerExecutor = Executors.newFixedThreadPool(Math.max(1, compilerThreads), runnable -> {
            Thread thread = new Thread(runnable, "SpigotLLM-agent-compiler");
            thread.setDaemon(true);
            return thread;
        });
        if (enabled && loadPersistentModules) loadPersistentModules();
    }

    public void tick() {
        ticks.tick();
        resources.cleanupExpired();
    }

    public void submit(final LeaseAccess lease, final AgentRequest request) {
        if (!open.get()) {
            respond(lease, AgentProtocol.error(request.getId(), "runtime_closed", "The agent runtime is shutting down."));
            return;
        }
        if (!enabled) {
            respond(lease, AgentProtocol.error(request.getId(), "tools_disabled",
                    "Agent runtime tools are disabled in config.yml."));
            return;
        }
        if (request.getLifecycle().getType() == Lifecycle.Type.TTL
                && request.getLifecycle().getTtlMillis() > maxTtlSeconds * 1000L) {
            respond(lease, AgentProtocol.error(request.getId(), "invalid_ttl",
                    "TTL exceeds the configured maximum of " + maxTtlSeconds + " seconds."));
            return;
        }
        if (request.getLifecycle().getType() == Lifecycle.Type.PERSISTENT
                && !"module.install".equals(request.getOperation())) {
            respond(lease, AgentProtocol.error(request.getId(), "persistent_module_required",
                    "Persistent executable behavior must be installed with module.install."));
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            new BukkitRunnable() {
                @Override public void run() { submit(lease, request); }
            }.runTask(plugin);
            return;
        }
        String operation = request.getOperation();
        try {
            if (operation.startsWith("reflect.")) {
                scheduleReflection(lease, request);
            } else if (operation.startsWith("code.")) {
                handleCode(lease, request);
            } else if (operation.startsWith("module.")) {
                handleModule(lease, request);
            } else if (operation.startsWith("event.")) {
                handleEvent(lease, request);
            } else if (operation.startsWith("command.")) {
                handleCommand(lease, request);
            } else if (operation.startsWith("schedule.")) {
                handleSchedule(lease, request);
            } else if (operation.startsWith("resource.")) {
                handleResource(lease, request);
            } else if ("console.execute".equals(operation)) {
                JsonObject args = request.getArguments();
                String command = requiredString(args, "command");
                audit(lease.owner(), operation, commandName(command));
                respond(lease, AgentProtocol.success(request, diagnostics.executeConsole(command)));
            } else if ("message.send".equals(operation)) {
                JsonObject args = request.getArguments();
                respond(lease, AgentProtocol.success(request,
                        diagnostics.sendMessage(optionalString(args, "target", "console"), requiredString(args, "message"))));
            } else if ("log.search".equals(operation)) {
                handleLogSearch(lease, request);
            } else if (operation.startsWith("snapshot.")) {
                respond(lease, AgentProtocol.success(request,
                        diagnostics.snapshot(operation.substring("snapshot.".length()))));
            } else if ("snapshot".equals(operation)) {
                respond(lease, AgentProtocol.success(request,
                        diagnostics.snapshot(optionalString(request.getArguments(), "kind", "server"))));
            } else {
                throw new RequestFailure("unknown_operation", "Unsupported agent operation: " + operation);
            }
        } catch (Throwable failure) {
            fail(lease, request, failure);
        }
    }

    public void closePrompt(final String owner, final String promptScope, final String handleOwner) {
        Runnable cleanup = new Runnable() {
            @Override public void run() {
                resources.closePromptScope(owner, promptScope);
                try { reflection.releaseOwner(handleOwner); } catch (ReflectionException ignored) { }
            }
        };
        if (Bukkit.isPrimaryThread()) cleanup.run();
        else if (plugin.isEnabled()) {
            new BukkitRunnable() { @Override public void run() { cleanup.run(); } }.runTask(plugin);
        }
    }

    public List<ResourceMetadata> listAll() {
        return resources.listAll();
    }

    public ResourceMetadata operatorInspect(String owner, String id) {
        return resources.inspect(owner, id);
    }

    public ResourceMetadata operatorEnable(String owner, String id) {
        ResourceMetadata metadata = resources.enable(owner, id);
        setPersistentEnabled(owner, id, metadata.isEnabled());
        audit(owner, "operator.resource.enable", id);
        return metadata;
    }

    public ResourceMetadata operatorDisable(String owner, String id) {
        ResourceMetadata metadata = resources.disable(owner, id);
        setPersistentEnabled(owner, id, false);
        audit(owner, "operator.resource.disable", id);
        return metadata;
    }

    public ResourceMetadata operatorRemove(String owner, String id) {
        ResourceMetadata metadata = resources.remove(owner, id);
        audit(owner, "operator.resource.remove", id);
        return metadata;
    }

    public int operatorPurge(boolean includePersistent) {
        int removed = 0;
        for (ResourceMetadata metadata : new ArrayList<ResourceMetadata>(resources.listAll())) {
            if (!includePersistent && metadata.getLifecycle().getType() == Lifecycle.Type.PERSISTENT) continue;
            try { resources.remove(metadata.getOwner(), metadata.getId()); removed++; }
            catch (RuntimeException ignored) { }
        }
        return removed;
    }

    private void scheduleReflection(final LeaseAccess lease, final AgentRequest request) {
        new BukkitRunnable() {
            @Override public void run() {
                if (!lease.active()) return;
                try {
                    JsonObject args = request.getArguments();
                    String member = optionalString(args, "member",
                            optionalString(args, "field",
                                    optionalString(args, "method", optionalString(args, "name", ""))));
                    audit(lease.owner(), request.getOperation(), member);
                    JsonElement result = reflection.dispatch(lease.handleOwner(), request.getOperation(), args);
                    respond(lease, AgentProtocol.success(request, result));
                } catch (Throwable failure) {
                    fail(lease, request, failure);
                }
            }
        }.runTask(plugin);
    }

    private void handleLogSearch(final LeaseAccess lease, final AgentRequest request) {
        try {
            compilerExecutor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JsonObject args = request.getArguments();
                        JsonObject result = diagnostics.searchLog(optionalString(args, "query", ""),
                                optionalInt(args, "maxLines", 100, 1, 500),
                                optionalInt(args, "maxBytes", 1024 * 1024, 4096, 4 * 1024 * 1024));
                        respond(lease, AgentProtocol.success(request, result));
                    } catch (Throwable failure) { fail(lease, request, failure); }
                }
            });
        } catch (RejectedExecutionException e) {
            throw new RequestFailure("runtime_closed", "The compiler worker is shutting down.");
        }
    }

    private void handleCode(final LeaseAccess lease, final AgentRequest request) {
        String operation = request.getOperation();
        if ("code.cancel".equals(operation)) {
            String id = requiredString(request.getArguments(), "resourceId");
            ResourceMetadata removed = resources.remove(lease.owner(), id);
            audit(lease.owner(), operation, id);
            respond(lease, AgentProtocol.success(request, gson.toJsonTree(removed)));
            return;
        }
        if (!("code.compile".equals(operation) || "code.run".equals(operation)
                || "code.runLater".equals(operation) || "code.runTimer".equals(operation))) {
            throw new RequestFailure("unknown_operation", "Unsupported code operation: " + operation);
        }
        final String source = requiredString(request.getArguments(), "source");
        compilerExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final CompiledSnippet compiled = compiler.compileSnippet(source);
                    audit(lease.owner(), request.getOperation(), compiled.getSourceHash());
                    if ("code.compile".equals(request.getOperation())) {
                        JsonObject result = new JsonObject();
                        result.addProperty("class", compiled.getGeneratedClassName());
                        result.addProperty("sourceHash", compiled.getSourceHash());
                        respond(lease, AgentProtocol.success(request, result));
                        return;
                    }
                    new BukkitRunnable() {
                        @Override public void run() { activateSnippet(lease, request, compiled); }
                    }.runTask(plugin);
                } catch (Throwable failure) { fail(lease, request, failure); }
            }
        });
    }

    private void activateSnippet(final LeaseAccess lease, AgentRequest request, CompiledSnippet compiled) {
        if (!lease.active()) return;
        String operation = request.getOperation();
        if ("code.run".equals(operation)) {
            String resourceId = "snippet-" + request.getId();
            BukkitMiniContext context = context(lease.owner(), lease.promptScope(), lease.handleOwner(),
                    resourceId, lease.eventsFile());
            long started = System.nanoTime();
            try {
                Object value = compiled.execute(context, plugin.getServer(), context::emit);
                warnSlow(lease.owner(), resourceId, started);
                JsonObject result = new JsonObject();
                result.addProperty("sourceHash", compiled.getSourceHash());
                result.add("value", reflection.encode(lease.handleOwner(), value));
                respond(lease, AgentProtocol.success(request, result));
            } catch (Throwable failure) {
                warnSlow(lease.owner(), resourceId, started);
                fail(lease, request, failure);
            } finally {
                context.close();
            }
            return;
        }
        JsonObject args = request.getArguments();
        String resourceId = optionalString(args, "resourceId", "code-" + request.getId());
        long delay = optionalLong(args, "delayTicks", 0L, 0L, Long.MAX_VALUE);
        long period = "code.runTimer".equals(operation)
                ? optionalLong(args, "periodTicks", 20L, 1L, Long.MAX_VALUE) : 0L;
        int maxRuns = "code.runTimer".equals(operation)
                ? optionalInt(args, "maxRuns", 0, 0, Integer.MAX_VALUE) : 1;
        String handleOwner = request.getLifecycle().getType() == Lifecycle.Type.PROMPT
                ? lease.handleOwner() : "resource:" + lease.owner() + ":" + resourceId;
        ScheduledSnippetResource resource = new ScheduledSnippetResource(lease.owner(), lease.promptScope(),
                handleOwner, resourceId, lease.eventsFile(), compiled, delay, period, maxRuns);
        ResourceMetadata metadata = resources.register(registration(lease, request, resourceId,
                "code.snippet", attributes("sourceHash", compiled.getSourceHash())), resource);
        respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
    }

    private void handleModule(final LeaseAccess lease, final AgentRequest request) {
        String operation = request.getOperation();
        if ("module.list".equals(operation)) {
            handleResourceList(lease, request, "module");
            return;
        }
        if ("module.enable".equals(operation) || "module.disable".equals(operation) || "module.remove".equals(operation)) {
            String id = requiredString(request.getArguments(), "resourceId");
            ResourceMetadata metadata = "module.enable".equals(operation) ? resources.enable(lease.owner(), id)
                    : "module.disable".equals(operation) ? resources.disable(lease.owner(), id)
                    : resources.remove(lease.owner(), id);
            if ("module.enable".equals(operation)) setPersistentEnabled(lease.owner(), id, metadata.isEnabled());
            if ("module.disable".equals(operation)) setPersistentEnabled(lease.owner(), id, false);
            audit(lease.owner(), operation, id);
            respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
            return;
        }
        if (!("module.install".equals(operation) || "module.compile".equals(operation))) {
            throw new RequestFailure("unknown_operation", "Unsupported module operation: " + operation);
        }
        final JsonObject args = request.getArguments();
        final String source = requiredString(args, "source");
        final String entryClass = requiredString(args, "entryClass");
        final String resourceId = optionalString(args, "resourceId", "module-" + request.getId());
        compilerExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final CompiledModule compiled = compiler.compileModule(source, entryClass);
                    if ("module.compile".equals(request.getOperation())) {
                        JsonObject result = new JsonObject();
                        result.addProperty("entryClass", compiled.getModuleClass().getName());
                        result.addProperty("sourceHash", compiled.getSourceHash());
                        respond(lease, AgentProtocol.success(request, result));
                        return;
                    }
                    new BukkitRunnable() {
                        @Override public void run() {
                            if (!lease.active() && request.getLifecycle().getType() == Lifecycle.Type.PROMPT) return;
                            try {
                                Path moduleEvents = request.getLifecycle().getType() == Lifecycle.Type.PERSISTENT
                                        ? persistentModuleDirectory(lease.owner(), resourceId).resolve("events.jsonl")
                                        : lease.eventsFile();
                                ModuleResource module = new ModuleResource(lease.owner(), lease.promptScope(),
                                        moduleHandleOwner(lease, resourceId, request.getLifecycle()), resourceId,
                                        moduleEvents, compiled, source, entryClass, request.getLifecycle());
                                String mapKey = moduleKey(lease.owner(), resourceId);
                                if (modules.putIfAbsent(mapKey, module) != null) {
                                    throw new RequestFailure("resource_exists",
                                            "A module with that resource id already exists for this owner.");
                                }
                                ResourceMetadata metadata;
                                try {
                                    metadata = resources.register(registration(lease, request, resourceId,
                                            "module", attributes("entryClass", entryClass,
                                                    "sourceHash", compiled.getSourceHash(),
                                                    "eventFile", moduleEvents.toString())), module);
                                    if (metadata.getState() == dev.foreground.spigotllm.agent.runtime.ResourceState.FAILED) {
                                        resources.remove(lease.owner(), resourceId);
                                        throw new RequestFailure("module_start_failed",
                                                "The mini-module failed during onEnable: " + metadata.getFailure());
                                    }
                                } catch (Throwable failure) {
                                    modules.remove(mapKey, module);
                                    throw failure;
                                }
                                audit(lease.owner(), "module.install", resourceId + ":" + compiled.getSourceHash());
                                respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
                            } catch (Throwable failure) { fail(lease, request, failure); }
                        }
                    }.runTask(plugin);
                } catch (Throwable failure) { fail(lease, request, failure); }
            }
        });
    }

    private void handleEvent(final LeaseAccess lease, final AgentRequest request) {
        String operation = request.getOperation();
        if ("event.unwatch".equals(operation)) {
            String id = requiredString(request.getArguments(), "resourceId");
            ResourceMetadata metadata = resources.remove(lease.owner(), id);
            audit(lease.owner(), operation, id);
            respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
            return;
        }
        if (!"event.watch".equals(operation) && !"event.await".equals(operation)) {
            throw new RequestFailure("unknown_operation", "Unsupported event operation: " + operation);
        }
        JsonObject args = request.getArguments();
        String className = requiredString(args, "eventClass");
        Class<? extends Event> type = StructuredEventWatch.resolveEvent(className, plugin.getClass().getClassLoader());
        String resourceId = optionalString(args, "resourceId", "event-" + request.getId());
        EventPriority priority = EventPriority.valueOf(optionalString(args, "priority", "MONITOR").toUpperCase(Locale.ROOT));
        boolean ignoreCancelled = optionalBoolean(args, "ignoreCancelled", true);
        List<String> captures = strings(args.get("capture"));
        if (captures.isEmpty()) captures = Arrays.asList("eventName");
        List<StructuredEventWatch.Filter> filters = filters(args.get("filters"));
        int maxMatches = "event.await".equals(operation) ? 1 : optionalInt(args, "maxMatches", 0, 0, Integer.MAX_VALUE);
        final AtomicBoolean completed = new AtomicBoolean();
        StructuredEventWatch.MatchListener listener = "event.await".equals(operation)
                ? new StructuredEventWatch.MatchListener() {
                    @Override public void matched(JsonObject event) {
                        if (!completed.compareAndSet(false, true)) return;
                        respond(lease, AgentProtocol.success(request, event));
                        removeQuietly(lease.owner(), resourceId);
                    }
                } : maxMatches > 0 ? new StructuredEventWatch.MatchListener() {
                    @Override public void matched(JsonObject event) {
                        if (event.has("match") && event.get("match").getAsInt() >= maxMatches) {
                            removeQuietly(lease.owner(), resourceId);
                        }
                    }
                } : null;
        final EventWatchResource resource = new EventWatchResource(resourceId, type, priority, ignoreCancelled,
                captures, filters, maxMatches, lease.eventsFile(), listener);
        ResourceMetadata metadata = resources.register(registration(lease, request, resourceId,
                "event.watch", attributes("eventClass", className)), resource);
        audit(lease.owner(), operation, className);
        if ("event.await".equals(operation)) {
            long timeoutTicks = optionalLong(args, "timeoutTicks", 200L, 1L, 36000L);
            final BukkitTask timeout = new BukkitRunnable() {
                @Override public void run() {
                    if (!completed.compareAndSet(false, true)) return;
                    respond(lease, AgentProtocol.error(request.getId(), "event_timeout", "No matching event arrived before the timeout."));
                    removeQuietly(lease.owner(), resourceId);
                }
            }.runTaskLater(plugin, timeoutTicks);
            resource.timeoutTask = timeout;
        } else {
            respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
        }
    }

    private void handleCommand(final LeaseAccess lease, final AgentRequest request) {
        String operation = request.getOperation();
        if ("command.remove".equals(operation)) {
            String id = requiredString(request.getArguments(), "resourceId");
            ResourceMetadata metadata = resources.remove(lease.owner(), id);
            audit(lease.owner(), operation, id);
            respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
            return;
        }
        if (!"command.create".equals(operation)) {
            throw new RequestFailure("unknown_operation", "Unsupported command operation: " + operation);
        }
        JsonObject args = request.getArguments();
        String resourceId = optionalString(args, "resourceId", "command-" + request.getId());
        DynamicCommandManager.Spec spec = new DynamicCommandManager.Spec(requiredString(args, "name"),
                strings(args.get("aliases")), optionalString(args, "description", null),
                optionalString(args, "usage", null), optionalString(args, "permission", null),
                optionalString(args, "permissionMessage", null),
                DynamicCommandManager.AccessMode.parse(optionalString(args, "access", "authorized")));
        StructuredCommandResource resource = new StructuredCommandResource(lease.owner(), resourceId, spec,
                optionalString(args, "reply", null), strings(args.get("consoleCommands")),
                strings(args.get("tabCompletions")), lease.eventsFile());
        ResourceMetadata metadata = resources.register(registration(lease, request, resourceId,
                "command", attributes("name", spec.name)), resource);
        audit(lease.owner(), operation, spec.name);
        respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
    }

    private void handleSchedule(final LeaseAccess lease, final AgentRequest request) {
        String operation = request.getOperation();
        if ("schedule.cancel".equals(operation)) {
            String id = requiredString(request.getArguments(), "resourceId");
            ResourceMetadata metadata = resources.remove(lease.owner(), id);
            audit(lease.owner(), operation, id);
            respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
            return;
        }
        if (!"schedule.once".equals(operation) && !"schedule.repeat".equals(operation)) {
            throw new RequestFailure("unknown_operation", "Unsupported schedule operation: " + operation);
        }
        JsonObject args = request.getArguments();
        String resourceId = optionalString(args, "resourceId", "schedule-" + request.getId());
        JsonObject action = requiredObject(args, "action");
        long delay = optionalLong(args, "delayTicks", 0L, 0L, Long.MAX_VALUE);
        long period = "schedule.repeat".equals(operation)
                ? optionalLong(args, "periodTicks", 20L, 1L, Long.MAX_VALUE) : 0L;
        int maxRuns = "schedule.repeat".equals(operation)
                ? optionalInt(args, "maxRuns", 0, 0, Integer.MAX_VALUE) : 1;
        StructuredScheduleResource resource = new StructuredScheduleResource(lease.owner(), resourceId, action, delay, period, maxRuns,
                lease.eventsFile());
        ResourceMetadata metadata = resources.register(registration(lease, request, resourceId,
                "schedule", attributes("action", optionalString(action, "type", "command"))), resource);
        audit(lease.owner(), operation, resourceId);
        respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
    }

    private void handleResource(LeaseAccess lease, AgentRequest request) {
        String operation = request.getOperation();
        if ("resource.list".equals(operation)) {
            handleResourceList(lease, request, optionalString(request.getArguments(), "type", null));
            return;
        }
        String id = requiredString(request.getArguments(), "resourceId");
        ResourceMetadata metadata;
        if ("resource.inspect".equals(operation)) metadata = resources.inspect(lease.owner(), id);
        else if ("resource.extend".equals(operation)) metadata = resources.extend(lease.owner(), id,
                optionalLong(request.getArguments(), "ttlSeconds", 300L, 1L, maxTtlSeconds));
        else if ("resource.enable".equals(operation)) metadata = resources.enable(lease.owner(), id);
        else if ("resource.disable".equals(operation)) metadata = resources.disable(lease.owner(), id);
        else if ("resource.remove".equals(operation)) metadata = resources.remove(lease.owner(), id);
        else throw new RequestFailure("unknown_operation", "Unsupported resource operation: " + operation);
        if ("resource.enable".equals(operation)) setPersistentEnabled(lease.owner(), id, metadata.isEnabled());
        if ("resource.disable".equals(operation)) setPersistentEnabled(lease.owner(), id, false);
        audit(lease.owner(), operation, id);
        respond(lease, AgentProtocol.success(request, gson.toJsonTree(metadata)));
    }

    private void handleResourceList(LeaseAccess lease, AgentRequest request, String type) {
        JsonArray result = new JsonArray();
        for (ResourceMetadata metadata : resources.list(lease.owner())) {
            if (type == null || metadata.getType().equals(type) || metadata.getType().startsWith(type + ".")) {
                result.add(gson.toJsonTree(metadata));
            }
        }
        respond(lease, AgentProtocol.success(request, result));
    }

    private ResourceRegistration registration(LeaseAccess lease, AgentRequest request, String id,
                                              String type, Map<String, String> attributes) {
        return new ResourceRegistration(id, lease.owner(), lease.promptScope(), type,
                request.getLifecycle(), attributes);
    }

    private BukkitMiniContext context(final String owner, final String promptScope, final String handleOwner,
                                      final String resourceId, final Path eventsFile) {
        BukkitMiniContext.HandleAccess handles = new BukkitMiniContext.HandleAccess() {
            @Override public String retain(Object value) {
                try { return reflection.getHandles().register(handleOwner, value); }
                catch (ReflectionException e) { throw new IllegalArgumentException(e.getMessage(), e); }
            }
            @Override public Object resolve(String handle) {
                try { return reflection.resolveHandle(handleOwner, handle); }
                catch (ReflectionException e) { throw new IllegalArgumentException(e.getMessage(), e); }
            }
            @Override public boolean release(String handle) {
                try { return reflection.getHandles().release(handleOwner, handle); }
                catch (ReflectionException e) { throw new IllegalArgumentException(e.getMessage(), e); }
            }
            @Override public JsonElement encode(Object value) {
                try { return reflection.encode(handleOwner, value); }
                catch (ReflectionException e) { return JsonNull.INSTANCE; }
            }
        };
        return new BukkitMiniContext(plugin, owner, resourceId, commands, miniReflection, handles,
                new BukkitMiniContext.EventSink() {
                    @Override public void emit(JsonObject event) {
                        eventWriter.emit(eventsFile, event);
                        if (event.has("type") && "code.slow".equals(event.get("type").getAsString())) {
                            audit(owner, "code.slow", resourceId + ":" + optionalString(event, "operation", "callback"));
                        }
                    }
                }, new BukkitMiniContext.FailureHandler() {
                    @Override public void failed(final Throwable error) { failResource(owner, resourceId, error); }
                }, slowMainThreadMillis, maxResourcesPerOwner);
    }

    private void failResource(final String owner, final String id, final Throwable error) {
        audit(owner, "resource.failure", id + ":" + error.getClass().getName());
        Runnable action = new Runnable() {
            @Override public void run() {
                try { resources.disable(owner, id); } catch (RuntimeException ignored) { }
                try { resources.markFailed(owner, id, error); } catch (RuntimeException ignored) { }
                setPersistentEnabled(owner, id, false);
            }
        };
        if (Bukkit.isPrimaryThread()) action.run();
        else if (plugin.isEnabled()) new BukkitRunnable() { @Override public void run() { action.run(); } }.runTask(plugin);
    }

    private void respond(LeaseAccess lease, JsonObject response) {
        if (lease == null || response == null || !lease.active()) return;
        JsonElement id = response.get("id");
        if (id == null || id.isJsonNull()) return;
        Path target = lease.responsesDirectory().resolve(id.getAsString() + ".json").normalize();
        if (!target.startsWith(lease.responsesDirectory().toAbsolutePath().normalize())) return;
        try { writeAtomic(target, gson.toJson(response) + "\n"); }
        catch (IOException e) { plugin.getLogger().warning("Could not write agent response " + id + ": " + e.getMessage()); }
    }

    private void fail(LeaseAccess lease, AgentRequest request, Throwable failure) {
        String code = "operation_failed";
        JsonElement details = null;
        if (failure instanceof RequestFailure) code = ((RequestFailure) failure).code;
        else if (failure instanceof ReflectionException) code = ((ReflectionException) failure).getCode();
        else if (failure instanceof ResourceRegistryException) code = ((ResourceRegistryException) failure).getCode();
        else if (failure instanceof CompilationException) {
            code = "compilation_failed";
            JsonArray diagnostics = new JsonArray();
            for (CompilationDiagnostic diagnostic : ((CompilationException) failure).getDiagnostics()) {
                JsonObject item = new JsonObject();
                if (diagnostic.getFileName() != null) item.addProperty("file", diagnostic.getFileName());
                item.addProperty("line", diagnostic.getLine());
                item.addProperty("column", diagnostic.getColumn());
                item.addProperty("message", diagnostic.getMessage());
                diagnostics.add(item);
            }
            details = diagnostics;
        }
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) message = failure.getClass().getSimpleName();
        if (lease != null && request != null) {
            audit(lease.owner(), request.getOperation() + ".failure", failure.getClass().getName());
        }
        respond(lease, AgentProtocol.error(request == null ? null : request.getId(), code, message, details));
    }

    private void audit(String owner, String operation, String target) {
        JsonObject record = new JsonObject();
        record.addProperty("type", "audit");
        record.addProperty("owner", owner);
        record.addProperty("operation", operation);
        if (target != null && !target.isEmpty()) record.addProperty("target", target);
        eventWriter.emit(auditFile, record);
    }

    private void warnSlow(String owner, String resourceId, long startedNanos) {
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        if (elapsed >= slowMainThreadMillis) {
            plugin.getLogger().warning("Agent code " + resourceId
                    + " blocked the server thread for " + elapsed + " ms.");
            audit(owner, "code.slow", resourceId);
        }
    }

    private void removeQuietly(final String owner, final String id) {
        Runnable removal = new Runnable() {
            @Override public void run() {
                try {
                    resources.remove(owner, id);
                    audit(owner, "resource.auto-remove", id);
                } catch (RuntimeException ignored) { }
            }
        };
        if (Bukkit.isPrimaryThread()) removal.run();
        else if (plugin.isEnabled()) new BukkitRunnable() { @Override public void run() { removal.run(); } }.runTask(plugin);
    }

    private String moduleHandleOwner(LeaseAccess lease, String resourceId, Lifecycle lifecycle) {
        return lifecycle.getType() == Lifecycle.Type.PROMPT ? lease.handleOwner()
                : "resource:" + lease.owner() + ":" + resourceId;
    }

    private void persistModule(ModuleResource module) throws IOException {
        Path directory = persistentModuleDirectory(module.owner, module.resourceId);
        Files.createDirectories(directory);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("owner", module.owner);
        manifest.addProperty("resourceId", module.resourceId);
        manifest.addProperty("entryClass", module.entryClass);
        manifest.addProperty("sourceHash", module.compiled.getSourceHash());
        manifest.addProperty("sourceFile", "module.java");
        manifest.addProperty("enabled", true);
        writeAtomic(directory.resolve("module.java"), module.source);
        writeAtomic(directory.resolve("module.json"), gson.toJson(manifest) + "\n");
        Files.deleteIfExists(directory.resolve("quarantine.marker"));
        module.persistentDirectory = directory;
        audit(module.owner, "module.persist", module.resourceId + ":" + module.compiled.getSourceHash());
    }

    private void setPersistentEnabled(String owner, String id, boolean enabled) {
        ModuleResource module = modules.get(moduleKey(owner, id));
        if (module == null || module.lifecycle.getType() != Lifecycle.Type.PERSISTENT
                || module.persistentDirectory == null) return;
        Path manifestFile = module.persistentDirectory.resolve("module.json");
        try {
            JsonObject manifest = new com.google.gson.JsonParser()
                    .parse(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8)).getAsJsonObject();
            manifest.addProperty("enabled", enabled);
            writeAtomic(manifestFile, gson.toJson(manifest) + "\n");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not update persistent module " + id + ": " + e.getMessage());
        }
    }

    private void markPersistentQuarantined(Path directory) {
        Path manifestFile = directory.resolve("module.json");
        try {
            writeAtomic(directory.resolve("quarantine.marker"), "quarantined\n");
            JsonObject manifest = new com.google.gson.JsonParser()
                    .parse(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8)).getAsJsonObject();
            manifest.addProperty("enabled", false);
            manifest.addProperty("quarantined", true);
            writeAtomic(manifestFile, gson.toJson(manifest) + "\n");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not persist quarantine state for " + directory.getFileName()
                    + ": " + e.getMessage());
        }
    }

    private void loadPersistentModules() throws IOException {
        Path persistentRoot = root.resolve("persistent-modules");
        if (!Files.isDirectory(persistentRoot)) return;
        try (java.nio.file.DirectoryStream<Path> owners = Files.newDirectoryStream(persistentRoot)) {
            for (Path ownerDirectory : owners) {
                if (!Files.isDirectory(ownerDirectory)) continue;
                try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(ownerDirectory)) {
                    for (Path directory : entries) {
                        if (!Files.isDirectory(directory)) continue;
                        loadPersistentModule(directory);
                    }
                }
            }
        }
    }

    private void loadPersistentModule(Path directory) {
        Path manifestFile = directory.resolve("module.json");
        Path sourceFile = directory.resolve("module.java");
        if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(sourceFile)) return;
        String owner = "unknown";
        String resourceId = directory.getFileName().toString();
        String entryClass = "unknown";
        try {
            JsonObject manifest = new com.google.gson.JsonParser()
                    .parse(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8)).getAsJsonObject();
            owner = requiredString(manifest, "owner");
            resourceId = requiredString(manifest, "resourceId");
            entryClass = requiredString(manifest, "entryClass");
            boolean enabled = optionalBoolean(manifest, "enabled", true);
            boolean quarantined = optionalBoolean(manifest, "quarantined", false)
                    || Files.isRegularFile(directory.resolve("quarantine.marker"));
            if (quarantined) {
                ResourceRegistration registration = new ResourceRegistration(resourceId, owner, "persistent", "module",
                        Lifecycle.persistent(), attributes("entryClass", entryClass,
                                "eventFile", directory.resolve("events.jsonl").toString()));
                resources.registerDisabled(registration, new QuarantinedModuleResource(directory));
                resources.markFailed(owner, resourceId,
                        new IllegalStateException("Persistent module is quarantined after a previous startup failure."));
                audit(owner, "module.quarantine", resourceId);
                return;
            }
            String source = new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
            CompiledModule compiled = compiler.compileModule(source, entryClass);
            String expectedHash = optionalString(manifest, "sourceHash", compiled.getSourceHash());
            if (!expectedHash.equals(compiled.getSourceHash())) {
                throw new IllegalArgumentException("Persistent module source hash does not match its manifest.");
            }
            String handleOwner = "resource:" + owner + ":" + resourceId;
            ModuleResource module = new ModuleResource(owner, "persistent", handleOwner, resourceId,
                    directory.resolve("events.jsonl"), compiled, source, entryClass, Lifecycle.persistent());
            module.persistentDirectory = directory;
            modules.put(moduleKey(owner, resourceId), module);
            ResourceRegistration registration = new ResourceRegistration(resourceId, owner, "persistent", "module",
                    Lifecycle.persistent(), attributes("entryClass", entryClass,
                            "sourceHash", compiled.getSourceHash(),
                            "eventFile", directory.resolve("events.jsonl").toString()));
            ResourceMetadata restored = enabled ? resources.register(registration, module)
                    : resources.registerDisabled(registration, module);
            if (restored.getState() == dev.foreground.spigotllm.agent.runtime.ResourceState.FAILED) {
                module.quarantined = true;
                setPersistentEnabled(owner, resourceId, false);
                markPersistentQuarantined(directory);
                plugin.getLogger().warning("Persistent agent module " + owner + "/" + resourceId
                        + " failed during startup and was quarantined.");
                audit(owner, "module.quarantine", resourceId);
                return;
            }
            audit(owner, "module.restore", resourceId + ":" + compiled.getSourceHash());
        } catch (Throwable failure) {
            plugin.getLogger().warning("Persistent agent module " + owner + "/" + resourceId
                    + " was quarantined after " + failure.getClass().getSimpleName() + ".");
            markPersistentQuarantined(directory);
            audit(owner, "module.quarantine", resourceId);
            try {
                ResourceRegistration registration = new ResourceRegistration(resourceId, owner, "persistent", "module",
                        Lifecycle.persistent(), attributes("entryClass", entryClass));
                resources.registerDisabled(registration, new QuarantinedModuleResource(directory));
                resources.markFailed(owner, resourceId, failure);
            } catch (RuntimeException ignored) { }
        }
    }

    private String moduleKey(String owner, String id) { return owner + "\n" + id; }

    private Path persistentModuleDirectory(String owner, String resourceId) {
        return root.resolve("persistent-modules").resolve(safePath(owner)).resolve(safePath(resourceId));
    }

    private void deletePersistent(ModuleResource module) {
        Path directory = module.persistentDirectory;
        if (directory == null || !directory.startsWith(root.resolve("persistent-modules"))) return;
        try {
            Files.deleteIfExists(directory.resolve("module.json"));
            Files.deleteIfExists(directory.resolve("module.java"));
            Files.deleteIfExists(directory.resolve("quarantine.marker"));
            Files.deleteIfExists(directory.resolve("events.jsonl"));
            Files.deleteIfExists(directory.resolve("events.jsonl.1"));
            Files.deleteIfExists(directory);
            audit(module.owner, "module.persistence.remove", module.resourceId);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not delete persistent module " + module.resourceId + ": " + e.getMessage());
        }
    }

    private static String safePath(String value) {
        String source = value == null ? "unknown" : value;
        String readable = source.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (readable.length() > 24) readable = readable.substring(0, 24);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (int index = 0; index < 8; index++) hash.append(String.format("%02x", digest[index] & 0xff));
            return readable + "-" + hash;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void writeAtomic(Path target, String value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(temporary, value.getBytes(StandardCharsets.UTF_8));
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        resources.shutdownClear();
        compilerExecutor.shutdownNow();
        eventWriter.close();
    }

    private final class ScheduledSnippetResource implements ManagedResource {
        private final String owner, promptScope, handleOwner, resourceId;
        private final Path eventsFile;
        private final CompiledSnippet compiled;
        private final long delay, period;
        private final int maxRuns;
        private BukkitMiniContext context;
        private BukkitTask task;
        private int runs;

        ScheduledSnippetResource(String owner, String promptScope, String handleOwner, String resourceId,
                                 Path eventsFile, CompiledSnippet compiled, long delay, long period, int maxRuns) {
            this.owner = owner; this.promptScope = promptScope; this.handleOwner = handleOwner;
            this.resourceId = resourceId; this.eventsFile = eventsFile; this.compiled = compiled;
            this.delay = delay; this.period = period; this.maxRuns = maxRuns;
        }

        @Override public void enable() {
            context = context(owner, promptScope, handleOwner, resourceId, eventsFile);
            BukkitRunnable runnable = new BukkitRunnable() {
                @Override public void run() {
                    long started = System.nanoTime();
                    try {
                        Object result = compiled.execute(context, plugin.getServer(), context::emit);
                        context.emit(result);
                        runs++;
                        if (maxRuns > 0 && runs >= maxRuns) removeQuietly(owner, resourceId);
                    } catch (Throwable failure) { failResource(owner, resourceId, failure); }
                    finally { warnSlow(owner, resourceId, started); }
                }
            };
            task = period > 0L ? runnable.runTaskTimer(plugin, delay, period) : runnable.runTaskLater(plugin, delay);
        }

        @Override public void disable() { if (task != null) task.cancel(); if (context != null) context.close(); }
        @Override public void close(RemovalReason reason) {
            disable();
            if (handleOwner.startsWith("resource:")) releaseHandles(handleOwner);
        }
    }

    private final class ModuleResource implements ManagedResource {
        private final String owner, promptScope, handleOwner, resourceId;
        private final Path eventsFile;
        private final CompiledModule compiled;
        private final String source, entryClass;
        private final Lifecycle lifecycle;
        private MiniModule instance;
        private BukkitMiniContext context;
        private Path persistentDirectory;
        private boolean quarantined;

        ModuleResource(String owner, String promptScope, String handleOwner, String resourceId, Path eventsFile,
                       CompiledModule compiled, String source, String entryClass, Lifecycle lifecycle) {
            this.owner = owner; this.promptScope = promptScope; this.handleOwner = handleOwner;
            this.resourceId = resourceId; this.eventsFile = eventsFile; this.compiled = compiled;
            this.source = source; this.entryClass = entryClass; this.lifecycle = lifecycle;
        }

        @Override public void enable() throws Exception {
            if (quarantined) throw new IllegalStateException("Persistent module is quarantined.");
            context = context(owner, promptScope, handleOwner, resourceId, eventsFile);
            instance = compiled.newInstance();
            try {
                instance.onEnable(context);
                if (lifecycle.getType() == Lifecycle.Type.PERSISTENT && persistentDirectory == null) persistModule(this);
            }
            catch (Exception e) {
                try { instance.onDisable(); } catch (Exception cleanupFailure) { e.addSuppressed(cleanupFailure); }
                try { context.close(); } catch (RuntimeException cleanupFailure) { e.addSuppressed(cleanupFailure); }
                context = null;
                instance = null;
                if (handleOwner.startsWith("resource:")) releaseHandles(handleOwner);
                throw e;
            }
        }

        @Override public void disable() throws Exception {
            Exception failure = null;
            if (instance != null) {
                try { instance.onDisable(); } catch (Exception e) { failure = e; }
            }
            instance = null;
            if (context != null) context.close();
            context = null;
            if (handleOwner.startsWith("resource:")) releaseHandles(handleOwner);
            if (failure != null) throw failure;
        }

        @Override public void close(RemovalReason reason) throws Exception {
            disable();
            if (lifecycle.getType() == Lifecycle.Type.PERSISTENT && reason != RemovalReason.SHUTDOWN) deletePersistent(this);
            modules.remove(moduleKey(owner, resourceId), this);
        }
    }

    private final class QuarantinedModuleResource implements ManagedResource {
        private final Path directory;
        QuarantinedModuleResource(Path directory) { this.directory = directory; }
        @Override public void enable() { throw new IllegalStateException("Persistent module is quarantined."); }
        @Override public void disable() { }
        @Override public void close(RemovalReason reason) {
            if (reason == RemovalReason.SHUTDOWN) return;
            try {
                Files.deleteIfExists(directory.resolve("module.json"));
                Files.deleteIfExists(directory.resolve("module.java"));
                Files.deleteIfExists(directory.resolve("quarantine.marker"));
                Files.deleteIfExists(directory.resolve("events.jsonl"));
                Files.deleteIfExists(directory.resolve("events.jsonl.1"));
                Files.deleteIfExists(directory);
            } catch (IOException e) {
                throw new IllegalStateException("Could not delete quarantined module: " + e.getMessage(), e);
            }
        }
    }

    private final class EventWatchResource implements ManagedResource {
        private final String resourceId;
        private final Class<? extends Event> eventType;
        private final EventPriority priority;
        private final boolean ignoreCancelled;
        private final List<String> capture;
        private final List<StructuredEventWatch.Filter> filters;
        private final int maxMatches;
        private final Path eventsFile;
        private final StructuredEventWatch.MatchListener matchListener;
        private StructuredEventWatch watch;
        private BukkitTask timeoutTask;

        EventWatchResource(String resourceId, Class<? extends Event> eventType, EventPriority priority,
                           boolean ignoreCancelled, List<String> capture, List<StructuredEventWatch.Filter> filters,
                           int maxMatches, Path eventsFile, StructuredEventWatch.MatchListener matchListener) {
            this.resourceId = resourceId; this.eventType = eventType; this.priority = priority;
            this.ignoreCancelled = ignoreCancelled; this.capture = capture; this.filters = filters;
            this.maxMatches = maxMatches; this.eventsFile = eventsFile; this.matchListener = matchListener;
        }

        @Override public void enable() {
            watch = new StructuredEventWatch(plugin, resourceId, eventType, priority, ignoreCancelled,
                    capture, filters, maxMatches, new StructuredEventWatch.Sink() {
                        @Override public void emit(JsonObject event) { eventWriter.emit(eventsFile, event); }
                    }, matchListener);
        }
        @Override public void disable() { if (watch != null) watch.close(); if (timeoutTask != null) timeoutTask.cancel(); }
        @Override public void close(RemovalReason reason) { disable(); }
    }

    private final class StructuredCommandResource implements ManagedResource {
        private final String owner;
        private final String resourceId;
        private final DynamicCommandManager.Spec spec;
        private final String reply;
        private final List<String> consoleCommands;
        private final List<String> tabCompletions;
        private final Path eventsFile;
        private DynamicCommandManager.Registration registration;

        StructuredCommandResource(String owner, String resourceId, DynamicCommandManager.Spec spec, String reply,
                                  List<String> consoleCommands, List<String> tabCompletions, Path eventsFile) {
            this.owner = owner; this.resourceId = resourceId; this.spec = spec; this.reply = reply;
            this.consoleCommands = consoleCommands; this.tabCompletions = tabCompletions; this.eventsFile = eventsFile;
        }

        @Override public void enable() {
            registration = commands.register(spec, new DynamicCommandManager.Handler() {
                @Override public boolean execute(CommandSender sender, String label, String[] arguments) throws Exception {
                    JsonObject event = new JsonObject();
                    event.addProperty("type", "command.invoked");
                    event.addProperty("resourceId", resourceId);
                    event.addProperty("sender", sender.getName());
                    event.addProperty("label", label);
                    JsonArray args = new JsonArray(); for (String argument : arguments) args.add(argument); event.add("arguments", args);
                    eventWriter.emit(eventsFile, event);
                    try {
                        if (reply != null) sender.sendMessage(template(reply, sender, label, arguments));
                        for (String command : consoleCommands) diagnostics.executeConsole(template(command, sender, label, arguments));
                        return true;
                    } catch (Throwable failure) {
                        JsonObject error = new JsonObject();
                        error.addProperty("type", "command.error");
                        error.addProperty("resourceId", resourceId);
                        error.addProperty("error", failure.getClass().getName());
                        eventWriter.emit(eventsFile, error);
                        failResource(owner, resourceId, failure);
                        if (failure instanceof Exception) throw (Exception) failure;
                        throw new Exception(failure);
                    }
                }
            }, tabCompletions.isEmpty() ? null : new DynamicCommandManager.Completion() {
                @Override public List<String> complete(CommandSender sender, String alias, String[] arguments) {
                    String prefix = arguments.length == 0 ? "" : arguments[arguments.length - 1].toLowerCase(Locale.ROOT);
                    List<String> result = new ArrayList<String>();
                    for (String candidate : tabCompletions) {
                        if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(candidate);
                    }
                    return result;
                }
            });
        }
        @Override public void disable() { if (registration != null) registration.close(); registration = null; }
        @Override public void close(RemovalReason reason) { disable(); }
    }

    private final class StructuredScheduleResource implements ManagedResource {
        private final String owner;
        private final String resourceId;
        private final JsonObject action;
        private final long delay, period;
        private final int maxRuns;
        private final Path eventsFile;
        private BukkitTask task;
        private int runs;

        StructuredScheduleResource(String owner, String resourceId, JsonObject action, long delay, long period,
                                   int maxRuns, Path eventsFile) {
            this.owner = owner; this.resourceId = resourceId; this.action = action.deepCopy(); this.delay = delay;
            this.period = period; this.maxRuns = maxRuns; this.eventsFile = eventsFile;
        }

        @Override public void enable() {
            BukkitRunnable runnable = new BukkitRunnable() {
                @Override public void run() {
                    try {
                        String type = optionalString(action, "type", "command");
                        if ("command".equalsIgnoreCase(type)) diagnostics.executeConsole(requiredString(action, "command"));
                        else if ("message".equalsIgnoreCase(type)) diagnostics.sendMessage(optionalString(action, "target", "console"), requiredString(action, "message"));
                        else throw new IllegalArgumentException("Scheduled action type must be command or message.");
                        JsonObject event = new JsonObject(); event.addProperty("type", "schedule.run");
                        event.addProperty("resourceId", resourceId); event.addProperty("run", ++runs); eventWriter.emit(eventsFile, event);
                        if (maxRuns > 0 && runs >= maxRuns) removeQuietly(owner, resourceId);
                    } catch (Throwable failure) {
                        JsonObject event = new JsonObject();
                        event.addProperty("type", "schedule.error");
                        event.addProperty("resourceId", resourceId);
                        event.addProperty("error", failure.getClass().getName());
                        eventWriter.emit(eventsFile, event);
                        plugin.getLogger().warning("Scheduled agent action " + resourceId + " failed with "
                                + failure.getClass().getSimpleName() + ".");
                        failResource(owner, resourceId, failure);
                    }
                }
            };
            task = period > 0L ? runnable.runTaskTimer(plugin, delay, period) : runnable.runTaskLater(plugin, delay);
        }
        @Override public void disable() { if (task != null) task.cancel(); }
        @Override public void close(RemovalReason reason) { disable(); }
    }

    private void releaseHandles(String handleOwner) {
        try { reflection.releaseOwner(handleOwner); } catch (ReflectionException ignored) { }
    }

    private static String template(String value, CommandSender sender, String label, String[] arguments) {
        String result = value.replace("%sender%", sender.getName()).replace("%label%", label)
                .replace("%args%", join(arguments));
        for (int index = 0; index < arguments.length; index++) result = result.replace("%arg" + index + "%", arguments[index]);
        return result;
    }

    private static String join(String[] values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(' '); result.append(value); }
        return result.toString();
    }

    private static Map<String, String> attributes(String... pairs) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < pairs.length; index += 2) result.put(pairs[index], pairs[index + 1]);
        return result;
    }

    private static String commandName(String command) {
        String clean = command == null ? "" : command.trim(); while (clean.startsWith("/")) clean = clean.substring(1).trim();
        int space = clean.indexOf(' '); return space < 0 ? clean : clean.substring(0, space);
    }

    private static String requiredString(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()
                || !object.get(field).isJsonPrimitive()) throw new RequestFailure("invalid_arguments", field + " is required.");
        String value = object.get(field).getAsString();
        if (value.trim().isEmpty()) throw new RequestFailure("invalid_arguments", field + " cannot be empty.");
        return value;
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        if (object == null || !object.has(field) || !object.get(field).isJsonObject())
            throw new RequestFailure("invalid_arguments", field + " must be an object.");
        return object.getAsJsonObject(field);
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        return object != null && object.has(field) && !object.get(field).isJsonNull()
                && object.get(field).isJsonPrimitive() ? object.get(field).getAsString() : fallback;
    }

    private static boolean optionalBoolean(JsonObject object, String field, boolean fallback) {
        try { return object != null && object.has(field) ? object.get(field).getAsBoolean() : fallback; }
        catch (RuntimeException e) { throw new RequestFailure("invalid_arguments", field + " must be boolean."); }
    }

    private static int optionalInt(JsonObject object, String field, int fallback, int min, int max) {
        try { int value = object != null && object.has(field) ? object.get(field).getAsInt() : fallback;
            if (value < min || value > max) throw new NumberFormatException(); return value; }
        catch (RuntimeException e) { throw new RequestFailure("invalid_arguments", field + " must be between " + min + " and " + max + "."); }
    }

    private static long optionalLong(JsonObject object, String field, long fallback, long min, long max) {
        try { long value = object != null && object.has(field) ? object.get(field).getAsLong() : fallback;
            if (value < min || value > max) throw new NumberFormatException(); return value; }
        catch (RuntimeException e) { throw new RequestFailure("invalid_arguments", field + " must be between " + min + " and " + max + "."); }
    }

    private static List<String> strings(JsonElement element) {
        if (element == null || element.isJsonNull()) return Collections.emptyList();
        if (!element.isJsonArray()) throw new RequestFailure("invalid_arguments", "Expected an array of strings.");
        List<String> result = new ArrayList<String>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive()) throw new RequestFailure("invalid_arguments", "Expected an array of strings.");
            result.add(value.getAsString());
        }
        return result;
    }

    private static List<StructuredEventWatch.Filter> filters(JsonElement element) {
        if (element == null || element.isJsonNull()) return Collections.emptyList();
        if (!element.isJsonArray()) throw new RequestFailure("invalid_arguments", "filters must be an array.");
        List<StructuredEventWatch.Filter> result = new ArrayList<StructuredEventWatch.Filter>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonObject()) throw new RequestFailure("invalid_arguments", "Each filter must be an object.");
            JsonObject object = value.getAsJsonObject();
            result.add(new StructuredEventWatch.Filter(requiredString(object, "path"),
                    optionalString(object, "operator", "equals"), object.get("value")));
        }
        return result;
    }

    public interface LeaseAccess {
        String owner();
        String promptScope();
        String handleOwner();
        Path responsesDirectory();
        Path eventsFile();
        boolean active();
    }

    private static final class RequestFailure extends RuntimeException {
        final String code;
        RequestFailure(String code, String message) { super(message); this.code = code; }
    }
}
