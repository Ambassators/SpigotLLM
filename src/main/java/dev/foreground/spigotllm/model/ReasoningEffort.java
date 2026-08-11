package dev.foreground.spigotllm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Provider-aware reasoning levels that can be applied to a named session. */
public enum ReasoningEffort {
    DEFAULT("default"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
    ULTRACODE("ultracode");

    private final String id;

    ReasoningEffort(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isDefault() {
        return this == DEFAULT;
    }

    public boolean supports(Provider provider) {
        if (this == DEFAULT) return true;
        if (provider == Provider.CODEX) {
            return this == MINIMAL || this == LOW || this == MEDIUM
                    || this == HIGH || this == XHIGH;
        }
        return provider == Provider.CLAUDE && (this == LOW || this == MEDIUM
                || this == HIGH || this == XHIGH || this == MAX || this == ULTRACODE);
    }

    public static ReasoningEffort parse(String input) {
        if (input == null) return null;
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized) || "provider-default".equals(normalized)) return DEFAULT;
        for (ReasoningEffort effort : values()) {
            if (effort.id.equals(normalized)) return effort;
        }
        return null;
    }

    public static String choices(Provider provider) {
        List<String> choices = new ArrayList<String>();
        for (ReasoningEffort effort : values()) {
            if (effort.supports(provider)) choices.add(effort.id);
        }
        return join(choices);
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append('|');
            result.append(value);
        }
        return result.toString();
    }
}
