package dev.foreground.spigotllm.agent.code;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface MiniTabCompleter {
    List<String> complete(CommandSender sender, String alias, String[] arguments) throws Exception;
}
