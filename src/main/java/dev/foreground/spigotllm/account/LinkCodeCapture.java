package dev.foreground.spigotllm.account;

import dev.foreground.spigotllm.PromptCoordinator;
import dev.foreground.spigotllm.access.AccessStore;
import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.output.PrivateMessenger;
import dev.foreground.spigotllm.provider.ProviderAdapter;
import dev.foreground.spigotllm.provider.ProviderException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Captures a one-time provider login code without placing it in the command log. */
public final class LinkCodeCapture implements Listener {
    private final AccessStore access;
    private final PromptCoordinator coordinator;
    private final PrivateMessenger messenger;
    private final Map<UUID, Provider> pending = new ConcurrentHashMap<UUID, Provider>();

    public LinkCodeCapture(AccessStore access, PromptCoordinator coordinator, PrivateMessenger messenger) {
        this.access = access;
        this.coordinator = coordinator;
        this.messenger = messenger;
    }

    public void arm(Player player, Provider provider) {
        pending.put(player.getUniqueId(), provider);
        messenger.info(player, "Your next chat message will be captured privately as the " + provider.id()
                + " login code. Use /sllm account code cancel to abort.");
    }

    public boolean cancel(Player player) {
        return pending.remove(player.getUniqueId()) != null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(final AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();
        final Provider provider = pending.remove(player.getUniqueId());
        if (provider == null) return;
        event.setCancelled(true);
        if (!player.isOp() || !access.isAuthorized(player.getUniqueId())) {
            messenger.error(player, "Access changed; the login code was discarded.");
            return;
        }
        String code = event.getMessage();
        messenger.info(player, "Submitting the private login code...");
        ProviderAdapter adapter = coordinator.adapter(provider);
        try {
            if (adapter == null || !adapter.submitLinkCode(Identity.player(player), code)) {
                messenger.error(player, "No " + provider.id() + " login is waiting for a code.");
            } else {
                messenger.success(player, "Login code submitted.");
            }
        } catch (ProviderException e) {
            messenger.error(player, e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
