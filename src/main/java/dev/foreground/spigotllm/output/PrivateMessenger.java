package dev.foreground.spigotllm.output;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sends output only to the command invoker and keeps paged answers per invoker. */
public final class PrivateMessenger {
    private static final String PREFIX = ChatColor.DARK_AQUA + "["
            + ChatColor.AQUA + ChatColor.BOLD + "SpigotLLM"
            + ChatColor.RESET + ChatColor.DARK_AQUA + "] " + ChatColor.RESET;

    private final JavaPlugin plugin;
    private final int linesPerPage;
    private final int maxLineLength;
    private final Map<String, List<List<String>>> pages = new ConcurrentHashMap<String, List<List<String>>>();

    public PrivateMessenger(JavaPlugin plugin, int linesPerPage, int maxLineLength) {
        this.plugin = plugin;
        this.linesPerPage = Math.max(1, linesPerPage);
        this.maxLineLength = Math.max(40, maxLineLength);
    }

    public void info(CommandSender sender, String message) {
        String clean = sanitize(message);
        send(sender, PREFIX + ChatColor.AQUA + "\u203a " + formatInfoBody(clean));
    }

    public void success(CommandSender sender, String message) {
        send(sender, PREFIX + ChatColor.GREEN + ChatColor.BOLD + "\u2713 "
                + ChatColor.RESET + ChatColor.GREEN + sanitize(message));
    }

    public void error(CommandSender sender, String message) {
        send(sender, PREFIX + ChatColor.RED + ChatColor.BOLD + "! "
                + ChatColor.RESET + ChatColor.RED + sanitize(message));
    }

    /** Sends a clickable line to players and a readable plain-text equivalent to the console. */
    public void interactive(final CommandSender sender, final Part... parts) {
        final String fallback = fallback(parts);
        if (!(sender instanceof Player)) {
            send(sender, fallback);
            return;
        }
        Runnable delivery = new Runnable() {
            @Override public void run() {
                try {
                    ((Player) sender).spigot().sendMessage(components(parts));
                } catch (LinkageError error) {
                    sender.sendMessage(fallback);
                } catch (RuntimeException error) {
                    sender.sendMessage(fallback);
                }
            }
        };
        deliver(delivery, ChatColor.stripColor(fallback));
    }

    public void response(CommandSender sender, String provider, String session, String response) {
        List<String> lines = wrap(response == null ? "" : response);
        if (lines.isEmpty()) lines = Collections.singletonList("(empty response)");
        List<List<String>> resultPages = new ArrayList<List<String>>();
        for (int index = 0; index < lines.size(); index += linesPerPage) {
            resultPages.add(new ArrayList<String>(lines.subList(index, Math.min(lines.size(), index + linesPerPage))));
        }
        pages.put(key(sender), resultPages);
        sendPage(sender, provider + " / " + session, resultPages, 1);
    }

    public boolean more(CommandSender sender, int page) {
        List<List<String>> resultPages = pages.get(key(sender));
        if (resultPages == null || resultPages.isEmpty()) {
            error(sender, "No paged response is available.");
            return false;
        }
        if (page < 1 || page > resultPages.size()) {
            error(sender, "Page must be between 1 and " + resultPages.size() + ".");
            return false;
        }
        sendPage(sender, "response", resultPages, page);
        return true;
    }

    private void sendPage(CommandSender sender, String title, List<List<String>> resultPages, int page) {
        List<String> pageLines = resultPages.get(page - 1);
        send(sender, PREFIX + ChatColor.GOLD + ChatColor.BOLD + sanitize(title)
                + ChatColor.RESET + ChatColor.DARK_GRAY + "  \u2022  "
                + ChatColor.YELLOW + "page " + page + "/" + resultPages.size());
        for (String line : pageLines) send(sender, formatResponseLine(line));
        if (page < resultPages.size()) {
            send(sender, ChatColor.YELLOW + "Next page: " + ChatColor.AQUA
                    + "/sllm more " + (page + 1));
        }
    }

    private String formatInfoBody(String clean) {
        if (clean.startsWith("/")) {
            int separator = clean.indexOf(" - ");
            if (separator > 0) {
                return ChatColor.AQUA + clean.substring(0, separator)
                        + ChatColor.DARK_GRAY + "  \u2014  "
                        + ChatColor.WHITE + clean.substring(separator + 3);
            }
            return ChatColor.AQUA + clean;
        }
        return ChatColor.WHITE + clean;
    }

    private String formatResponseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return ChatColor.DARK_GRAY + "\u2502";
        if (trimmed.startsWith("#")) return ChatColor.GOLD + "" + ChatColor.BOLD + line;
        if (trimmed.startsWith("```") || line.startsWith("    ")) return ChatColor.YELLOW + line;
        if (trimmed.startsWith(">")) return ChatColor.GRAY + "" + ChatColor.ITALIC + line;
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return ChatColor.AQUA + line;
        }
        if (trimmed.matches("\\d+[.)]\\s+.*")) return ChatColor.GREEN + line;
        return ChatColor.WHITE + line;
    }

    private List<String> wrap(String raw) {
        String clean = sanitize(raw).replace("\r\n", "\n").replace('\r', '\n');
        List<String> output = new ArrayList<String>();
        String[] sourceLines = clean.split("\n", -1);
        for (String source : sourceLines) {
            if (source.isEmpty()) {
                output.add(" ");
                continue;
            }
            String remaining = source;
            while (remaining.length() > maxLineLength) {
                int split = remaining.lastIndexOf(' ', maxLineLength);
                if (split < maxLineLength / 2) split = maxLineLength;
                output.add(remaining.substring(0, split).trim());
                remaining = remaining.substring(split).trim();
            }
            if (!remaining.isEmpty()) output.add(remaining);
        }
        return output;
    }

    private String sanitize(String value) {
        if (value == null) return "";
        return value.replace('\u00a7', '?')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .trim();
    }

    private String sanitizeInline(String value) {
        if (value == null) return "";
        return value.replace('\u00a7', '?')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
    }

    private BaseComponent[] components(Part[] parts) {
        List<BaseComponent> output = new ArrayList<BaseComponent>();
        TextComponent prefix = new TextComponent("[SpigotLLM] ");
        prefix.setColor(net.md_5.bungee.api.ChatColor.AQUA);
        prefix.setBold(true);
        output.add(prefix);
        if (parts != null) {
            for (Part part : parts) {
                if (part == null) continue;
                TextComponent component = new TextComponent(sanitizeInline(part.text));
                component.setColor(net.md_5.bungee.api.ChatColor.valueOf(part.color.name()));
                component.setBold(part.bold);
                if (part.command != null) {
                    component.setClickEvent(new ClickEvent(part.suggest
                            ? ClickEvent.Action.SUGGEST_COMMAND : ClickEvent.Action.RUN_COMMAND,
                            part.command));
                }
                if (part.hover != null) {
                    component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new BaseComponent[] { new TextComponent(sanitizeInline(part.hover)) }));
                }
                output.add(component);
            }
        }
        return output.toArray(new BaseComponent[output.size()]);
    }

    private String fallback(Part[] parts) {
        StringBuilder output = new StringBuilder(PREFIX);
        if (parts != null) {
            for (Part part : parts) {
                if (part == null) continue;
                output.append(part.color);
                if (part.bold) output.append(ChatColor.BOLD);
                output.append(sanitizeInline(part.text));
            }
        }
        return output.toString();
    }

    private String key(CommandSender sender) {
        return sender instanceof Player
                ? "player:" + ((Player) sender).getUniqueId().toString()
                : "console";
    }

    private void send(final CommandSender sender, final String message) {
        Runnable delivery = new Runnable() {
            @Override public void run() { sender.sendMessage(message); }
        };
        deliver(delivery, ChatColor.stripColor(message));
    }

    private void deliver(Runnable delivery, String fallbackLogMessage) {
        if (Bukkit.isPrimaryThread()) {
            delivery.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, delivery);
        } else {
            plugin.getLogger().info(fallbackLogMessage);
        }
    }

    public static final class Part {
        private final ChatColor color;
        private final String text;
        private final boolean bold;
        private final String command;
        private final String hover;
        private final boolean suggest;

        private Part(ChatColor color, String text, boolean bold, String command, String hover, boolean suggest) {
            this.color = color == null ? ChatColor.WHITE : color;
            this.text = text == null ? "" : text;
            this.bold = bold;
            this.command = command;
            this.hover = hover;
            this.suggest = suggest;
        }

        public static Part text(ChatColor color, String text) {
            return new Part(color, text, false, null, null, false);
        }

        public static Part bold(ChatColor color, String text) {
            return new Part(color, text, true, null, null, false);
        }

        public static Part run(ChatColor color, String text, String command, String hover) {
            return new Part(color, text, false, command, hover, false);
        }

        public static Part suggest(ChatColor color, String text, String command, String hover) {
            return new Part(color, text, false, command, hover, true);
        }
    }
}
