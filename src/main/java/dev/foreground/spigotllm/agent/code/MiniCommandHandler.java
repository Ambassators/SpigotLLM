package dev.foreground.spigotllm.agent.code;

import org.bukkit.command.CommandSender;

public interface MiniCommandHandler {
    boolean execute(CommandSender sender, String label, String[] arguments) throws Exception;
}
