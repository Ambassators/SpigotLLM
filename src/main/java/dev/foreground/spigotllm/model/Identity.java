package dev.foreground.spigotllm.model;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class Identity {
    public static final Identity SERVER = new Identity("server", "Server Console", null);

    private final String key;
    private final String displayName;
    private final UUID playerUuid;

    private Identity(String key, String displayName, UUID playerUuid) {
        this.key = key;
        this.displayName = displayName;
        this.playerUuid = playerUuid;
    }

    public static Identity player(Player player) {
        return new Identity("player-" + player.getUniqueId().toString(), player.getName(), player.getUniqueId());
    }

    public static Identity from(CommandSender sender) {
        return sender instanceof Player ? player((Player) sender) : SERVER;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public UUID playerUuid() {
        return playerUuid;
    }
}
