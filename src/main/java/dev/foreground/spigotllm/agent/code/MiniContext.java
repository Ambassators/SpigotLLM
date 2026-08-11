package dev.foreground.spigotllm.agent.code;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.logging.Level;

/**
 * Host services exposed to generated modules. Implementations must track and
 * clean up everything registered or scheduled through this interface.
 */
public interface MiniContext {
    Server server();

    String owner();

    String resourceId();

    Object emit(Object value);

    void log(Level level, String message);

    void log(Level level, String message, Throwable error);

    <T extends Event> MiniRegistration listen(Class<T> eventType, EventPriority priority,
                                               boolean ignoreCancelled, MiniEventHandler<T> handler);

    MiniRegistration command(String label, List<String> aliases, String usage,
                             MiniCommandAccess access, String permission,
                             MiniCommandHandler handler, MiniTabCompleter tabCompleter);

    BukkitTask runSync(BukkitRunnable task);

    BukkitTask runLater(BukkitRunnable task, long delayTicks);

    BukkitTask runTimer(BukkitRunnable task, long delayTicks, long periodTicks);

    MiniReflection reflection();

    String retain(Object value);

    Object resolve(String handle);

    boolean release(String handle);

    boolean dispatchCommand(CommandSender sender, String commandLine);

    void sendMessage(CommandSender recipient, String message);
}
