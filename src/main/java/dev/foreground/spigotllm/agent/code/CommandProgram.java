package dev.foreground.spigotllm.agent.code;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;

/** Internal ABI implemented by generated command callbacks. */
public interface CommandProgram {
    Object run(MiniContext context, Server server, MiniEmitter emitter,
               CommandSender sender, String label, String[] args) throws Exception;
}
