package dev.foreground.spigotllm.agent.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Result of a bulk cleanup; cleanup continues after individual callback failures. */
public final class CleanupReport {
    private final int removed;
    private final Map<String, String> failures;

    CleanupReport(int removed, Map<String, String> failures) {
        this.removed = removed;
        this.failures = Collections.unmodifiableMap(new LinkedHashMap<String, String>(failures));
    }

    public int getRemoved() {
        return removed;
    }

    public Map<String, String> getFailures() {
        return failures;
    }

    public boolean isSuccessful() {
        return failures.isEmpty();
    }
}
