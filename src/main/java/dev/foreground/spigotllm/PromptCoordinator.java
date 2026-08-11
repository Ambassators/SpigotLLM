package dev.foreground.spigotllm;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.SessionMode;
import dev.foreground.spigotllm.output.PrivateMessenger;
import dev.foreground.spigotllm.provider.ProgressListener;
import dev.foreground.spigotllm.provider.PromptResult;
import dev.foreground.spigotllm.provider.ProviderAdapter;
import dev.foreground.spigotllm.provider.ProviderException;
import dev.foreground.spigotllm.session.SessionRecord;
import dev.foreground.spigotllm.session.SessionStore;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PromptCoordinator {
    private final JavaPlugin plugin;
    private final SessionStore sessions;
    private final PrivateMessenger messenger;
    private final Map<Provider, ProviderAdapter> adapters = new EnumMap<Provider, ProviderAdapter>(Provider.class);
    private final Map<String, Boolean> activeSessions = new ConcurrentHashMap<String, Boolean>();
    private final AtomicInteger activePromptCount = new AtomicInteger();
    private final AtomicBoolean maintenance = new AtomicBoolean();
    private final ExecutorService executor;

    public PromptCoordinator(JavaPlugin plugin, SessionStore sessions, PrivateMessenger messenger,
                             int maxConcurrentPrompts, Map<Provider, ProviderAdapter> providerAdapters) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.messenger = messenger;
        this.adapters.putAll(providerAdapters);
        final AtomicInteger number = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, maxConcurrentPrompts), runnable -> {
            Thread thread = new Thread(runnable, "SpigotLLM-worker-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean submit(final CommandSender sender, final Identity identity, final Provider provider,
                          final SessionMode mode, final String prompt) {
        if (maintenance.get()) {
            messenger.error(sender, "A provider runtime operation is in progress. Try again when it finishes.");
            return false;
        }
        final SessionRecord session;
        try {
            session = sessions.getOrCreateActive(identity, provider, mode);
        } catch (IOException e) {
            messenger.error(sender, "Could not load the active session: " + e.getMessage());
            return false;
        }
        final String activeKey = identity.key() + "|" + session.key();
        if (activeSessions.putIfAbsent(activeKey, Boolean.TRUE) != null) {
            messenger.error(sender, "That thread already has a prompt running. Use /sllm cancel " + provider.id() + ".");
            return false;
        }
        activePromptCount.incrementAndGet();
        if (maintenance.get()) {
            activeSessions.remove(activeKey);
            activePromptCount.decrementAndGet();
            messenger.error(sender, "A provider runtime operation is in progress. Try again when it finishes.");
            return false;
        }
        String effort = session.getReasoningEffort().isDefault()
                ? "provider-default" : session.getReasoningEffort().id();
        messenger.info(sender, "Sending a private " + mode.id() + " prompt to " + provider.id()
                + " (thread " + session.getName() + ", effort " + effort + ")...");
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final AtomicLong lastProgress = new AtomicLong();
                        ProgressListener progress = new ProgressListener() {
                            @Override public void onProgress(String message) {
                                long now = System.currentTimeMillis();
                                long previous = lastProgress.get();
                                if (now - previous >= 1000L && lastProgress.compareAndSet(previous, now)) {
                                    messenger.info(sender, message);
                                }
                            }
                        };
                        ProviderAdapter adapter = adapters.get(provider);
                        if (adapter == null) throw new ProviderException("Provider is not configured.");
                        PromptResult result = adapter.prompt(identity, session, prompt, progress);
                        sessions.updateProviderId(identity, session, result.providerSessionId());
                        messenger.response(sender, provider.id(), session.getName(), result.response());
                    } catch (ProviderException e) {
                        messenger.error(sender, e.getMessage());
                    } catch (IOException e) {
                        messenger.error(sender, "Could not save the provider session: " + e.getMessage());
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Provider task failed for " + identity.key() + ": " + e.getClass().getSimpleName());
                        messenger.error(sender, "The provider task failed unexpectedly. Check the server log.");
                    } catch (LinkageError e) {
                        plugin.getLogger().warning("Provider dependency failed for " + identity.key() + ": "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                        messenger.error(sender, "A bundled provider dependency failed to load. Update the SpigotLLM JAR.");
                    } finally {
                        activeSessions.remove(activeKey);
                        activePromptCount.decrementAndGet();
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            activeSessions.remove(activeKey);
            activePromptCount.decrementAndGet();
            messenger.error(sender, "SpigotLLM is shutting down.");
            return false;
        }
    }

    public boolean executeBackground(final CommandSender sender, final Runnable task) {
        try {
            executor.execute(new Runnable() {
                @Override public void run() {
                    try {
                        task.run();
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Background command failed: " + e.getClass().getSimpleName());
                        messenger.error(sender, "The command failed unexpectedly. Check the server log.");
                    } catch (LinkageError e) {
                        plugin.getLogger().warning("Background dependency failed: " + e.getClass().getSimpleName()
                                + ": " + e.getMessage());
                        messenger.error(sender, "A bundled dependency failed to load. Update the SpigotLLM JAR.");
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            messenger.error(sender, "SpigotLLM is shutting down.");
            return false;
        }
    }

    public ProviderAdapter adapter(Provider provider) {
        return adapters.get(provider);
    }

    public boolean cancel(Identity identity, Provider provider) {
        if (provider != null) {
            ProviderAdapter adapter = adapters.get(provider);
            return adapter != null && adapter.cancel(identity);
        }
        boolean cancelled = false;
        for (ProviderAdapter adapter : adapters.values()) cancelled |= adapter.cancel(identity);
        return cancelled;
    }

    public boolean isBusy() {
        return activePromptCount.get() > 0;
    }

    public boolean beginRuntimeOperation() {
        if (!maintenance.compareAndSet(false, true)) return false;
        if (activePromptCount.get() > 0) {
            maintenance.set(false);
            return false;
        }
        return true;
    }

    public void endRuntimeOperation() {
        maintenance.set(false);
    }

    public boolean isBusy(Identity identity) {
        String prefix = identity.key() + "|";
        for (String key : activeSessions.keySet()) if (key.startsWith(prefix)) return true;
        return false;
    }

    public void shutdown() {
        for (ProviderAdapter adapter : adapters.values()) adapter.shutdown();
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
