package dev.foreground.spigotllm.agent.spark;

import com.google.gson.JsonObject;

import java.util.*;

/** Accident-prevention guard, not a sandbox: existing console/code tools remain privileged. */
final class SparkCommandPolicy {
    private SparkCommandPolicy() { }

    static String build(JsonObject args, int maxSeconds) {
        String input = SparkJson.text(args, "command", "");
        if (input.isEmpty() || input.length() > 2048) throw invalid("A Spark subcommand of 1..2048 characters is required.");
        for (int i = 0; i < input.length(); i++) {
            if (Character.isISOControl(input.charAt(i))) throw invalid("Control characters are not allowed.");
        }
        input = input.trim();
        if (input.isEmpty()) throw invalid("A Spark subcommand is required.");
        String[] words = input.split(" +");
        String root = words[0].toLowerCase(Locale.ROOT);
        boolean changesState;
        int flagStart = 1;
        Set<String> flags = new HashSet<String>();
        if ("profiler".equals(root)) {
            if (words.length < 2) throw invalid("Use profiler info/start/stop/cancel/open explicitly.");
            String action = words[1].toLowerCase(Locale.ROOT);
            flagStart = 2;
            changesState = !"info".equals(action);
            if ("start".equals(action)) {
                Collections.addAll(flags, "timeout", "thread", "regex", "combine-all", "not-combined", "interval",
                        "only-ticks-over", "force-java-sampler", "ignore-sleeping", "alloc", "alloc-live-only", "save-to-file");
            } else if ("stop".equals(action)) {
                Collections.addAll(flags, "comment", "save-to-file");
            } else if (!("info".equals(action) || "cancel".equals(action) || "open".equals(action))) {
                throw invalid("Unsupported profiler action.");
            }
        } else if ("tps".equals(root) || "cpu".equals(root) || "gc".equals(root)) {
            changesState = false;
        } else if ("ping".equals(root)) {
            changesState = false;
            flags.add("player");
        } else if ("activity".equals(root)) {
            changesState = false;
            flags.add("page");
        } else if ("health".equals(root) || "healthreport".equals(root)) {
            // Bare health may open/upload a live report on newer Spark versions.
            changesState = true;
            if (words.length > 1 && !words[1].startsWith("--")) {
                String action = words[1].toLowerCase(Locale.ROOT);
                if (!("show".equals(action) || "upload".equals(action) || "dashboard".equals(action))) throw invalid("Use health show, health upload, or health dashboard.");
                changesState = !"show".equals(action);
                flagStart = 2;
            }
            Collections.addAll(flags, "memory", "network");
        } else if ("heapsummary".equals(root)) {
            changesState = true;
            Collections.addAll(flags, "run-gc-before", "save-to-file");
        } else if ("heapdump".equals(root)) {
            changesState = true;
            Collections.addAll(flags, "compress", "run-gc-before", "include-non-live");
        } else if ("gcmonitor".equals(root)) {
            changesState = true;
        } else if ("tickmonitor".equals(root)) {
            changesState = true;
            Collections.addAll(flags, "threshold", "threshold-tick", "without-gc");
        } else {
            throw invalid("Unsupported Spark subcommand. Read SPARK.md for the supported suite.");
        }
        Map<String, String> options = parseFlags(words, flagStart, flags);
        if (changesState && !SparkJson.bool(args, "confirm", false)) {
            throw new SparkException("spark_confirmation_required",
                    "This command changes shared Spark state, can pause the JVM, or can upload server diagnostics. Obtain user authorization, then pass confirm:true. Do not stop another administrator's profiler or toggle shared monitors blindly.");
        }
        if (options.containsKey("page")) positiveInteger(options.get("page"), 1, 10000, "page");
        for (String flag : new String[]{"threshold", "threshold-tick", "only-ticks-over"}) {
            if (options.containsKey(flag)) positiveInteger(options.get(flag), 1, 60000, flag);
        }
        if (options.containsKey("compress") && !Arrays.asList("gzip", "xz", "lzma").contains(options.get("compress"))) {
            throw invalid("Compression must be gzip, xz, or lzma.");
        }
        if ("profiler".equals(root) && "start".equalsIgnoreCase(words[1])) {
            maxSeconds = Math.max(11, Math.min(3600, maxSeconds));
            if (options.containsKey("timeout")) positiveInteger(options.get("timeout"), 11, maxSeconds, "timeout");
            else input += " --timeout " + Math.min(60, maxSeconds);
            if (options.containsKey("interval")) {
                double interval;
                try { interval = Double.parseDouble(options.get("interval")); }
                catch (NumberFormatException e) { throw invalid("interval must be numeric."); }
                boolean allocation = options.containsKey("alloc");
                if (!Double.isFinite(interval) || interval < (allocation ? 1024 : 1) || interval > (allocation ? 1073741824 : 1000)) {
                    throw invalid("interval must be 1..1000 milliseconds, or 1024..1073741824 bytes for --alloc.");
                }
            }
            if (options.containsKey("alloc") && options.containsKey("force-java-sampler")) throw invalid("Allocation profiling requires the native engine; do not force the Java sampler.");
            if (options.containsKey("alloc-live-only") && !options.containsKey("alloc")) throw invalid("--alloc-live-only requires --alloc.");
            if (options.containsKey("combine-all") && options.containsKey("not-combined")) throw invalid("Conflicting thread grouping flags.");
        }
        return "spark " + input;
    }

    private static Map<String, String> parseFlags(String[] words, int start, Set<String> allowed) {
        Set<String> valued = new HashSet<String>(Arrays.asList("timeout", "thread", "interval", "only-ticks-over",
                "comment", "player", "page", "compress", "threshold", "threshold-tick"));
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = start; i < words.length; i++) {
            if (!words[i].startsWith("--")) throw invalid("Unexpected argument; flag values follow --flag.");
            String name = words[i].substring(2);
            if (!allowed.contains(name) || result.containsKey(name)) throw invalid("Unsupported or duplicate flag: " + name);
            StringBuilder value = new StringBuilder();
            if (valued.contains(name)) {
                while (i + 1 < words.length && !words[i + 1].startsWith("--")) {
                    if (value.length() > 0) value.append(' ');
                    value.append(words[++i]);
                }
                if (value.length() == 0) throw invalid("--" + name + " needs a value.");
            }
            result.put(name, value.toString());
        }
        return result;
    }

    private static void positiveInteger(String text, int min, int max, String flag) {
        try {
            int number = Integer.parseInt(text);
            if (number >= min && number <= max) return;
        } catch (NumberFormatException ignored) { }
        throw invalid(flag + " must be an integer in " + min + ".." + max + ".");
    }

    private static SparkException invalid(String message) { return new SparkException("spark_invalid_command", message); }
}
