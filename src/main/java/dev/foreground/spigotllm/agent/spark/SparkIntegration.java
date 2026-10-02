package dev.foreground.spigotllm.agent.spark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bukkit-facing optional Spark integration. Bukkit calls remain on the main thread;
 * all report network/file IO and analysis runs on a bounded dedicated worker.
 */
public final class SparkIntegration implements AutoCloseable {
    private final Server server;
    private final Path consoleLog, reportsRoot;
    private final boolean enabled, remoteEnabled;
    private final int maxProfilerSeconds, maxReportBytes;
    private final SparkReportSource reports;
    private final ThreadPoolExecutor worker;

    public SparkIntegration(JavaPlugin plugin, Path consoleLog, Path reportsRoot) throws IOException {
        this(plugin.getServer(), consoleLog, reportsRoot, plugin.getConfig());
    }

    SparkIntegration(Server server, Path consoleLog, Path reportsRoot, FileConfiguration config) throws IOException {
        this.server = server;
        this.consoleLog = consoleLog;
        this.reportsRoot = reportsRoot.toAbsolutePath().normalize();
        enabled = config.getBoolean("spark.enabled", true);
        remoteEnabled = config.getBoolean("spark.allow-remote-reports", true);
        maxProfilerSeconds = Math.max(11, Math.min(3600, config.getInt("spark.max-profiler-seconds", 300)));
        maxReportBytes = Math.max(1024, Math.min(64 * 1024 * 1024, config.getInt("spark.max-report-bytes", 16777216)));
        reports = new SparkReportSource(this.reportsRoot, remoteEnabled, maxReportBytes,
                Math.max(1, Math.min(60, config.getInt("spark.http-timeout-seconds", 20))) * 1000);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(4), runnable -> {
                    Thread thread = new Thread(runnable, "SpigotLLM-spark-reports");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public JsonObject status() {
        JsonObject result = new JsonObject();
        result.addProperty("enabled", enabled);
        Plugin spark = server.getPluginManager().getPlugin("spark");
        result.addProperty("standalonePluginEnabled", spark != null && spark.isEnabled());
        if (spark != null) result.addProperty("standalonePluginVersion", spark.getDescription().getVersion());
        result.addProperty("apiAvailable", SparkApiAccess.find(server) != null);
        result.addProperty("integration", "stock-spark-public-api-and-commands");
        result.addProperty("forkRequired", false);
        result.addProperty("remoteReportsEnabled", remoteEnabled);
        result.addProperty("localReportDirectory", reportsRoot.toString());
        result.addProperty("maxReportBytes", maxReportBytes);
        result.addProperty("maxProfilerSeconds", maxProfilerSeconds);
        result.addProperty("reportQueueDepth", worker.getQueue().size());
        JsonArray operations = new JsonArray();
        for (String operation : new String[]{"spark.status", "spark.snapshot", "spark.command", "spark.report"}) operations.add(operation);
        result.add("operations", operations);
        result.addProperty("compatibility", "A registered public Spark service also supports bundled installations. Command/engine/metric availability depends on the installed Spark, server and JVM versions. Report analysis works without an installed Spark plugin.");
        return result;
    }

    public JsonObject snapshot() {
        requireEnabled();
        SparkApiAccess api = SparkApiAccess.find(server);
        if (api == null) throw new SparkException("spark_unavailable", "Spark's public API service is not registered. Install/enable a compatible stock Spark build, or analyze a report with spark.report. Other SpigotLLM tools are unaffected.");
        return api.snapshot();
    }

    public JsonObject command(JsonObject args) {
        requireEnabled();
        String command = SparkCommandPolicy.build(args, maxProfilerSeconds);
        long dispatchedAt = System.currentTimeMillis();
        boolean accepted = server.dispatchCommand(server.getConsoleSender(), command);
        JsonObject result = new JsonObject();
        result.addProperty("status", accepted ? "dispatched" : "rejected");
        result.addProperty("command", command);
        result.addProperty("consoleLog", consoleLog.toString());
        result.addProperty("completed", false);
        result.addProperty("dispatchedAtMillis", dispatchedAt);
        result.addProperty("nextStep", "Use log.search/the live console log to verify Spark's actual response and obtain the report link, then call spark.report. Dispatch acceptance does not prove profiling, monitoring, dumping or upload succeeded. Unsupported options and foreground-profiler conflicts are reported by Spark.");
        result.addProperty("lifecycle", "Spark profilers/monitors are server-global, not lease-owned. Timed starts have an explicit timeout; closing this agent lease does not toggle or cancel shared Spark state.");
        return result;
    }

    public void report(JsonObject args, BooleanSupplier active, Consumer<JsonObject> success, Consumer<Throwable> failure) {
        requireEnabled();
        final JsonObject arguments = args.deepCopy();
        try {
            worker.execute(() -> {
                if (!active.getAsBoolean()) return;
                try {
                    JsonObject input = reports.load(arguments);
                    if (!active.getAsBoolean() || Thread.currentThread().isInterrupted()) return;
                    JsonObject result = new SparkReportAnalyzer().analyze(input, arguments);
                    if (result.toString().getBytes(StandardCharsets.UTF_8).length > 262144) {
                        throw new SparkException("spark_summary_too_large", "Summary exceeds 256 KiB. Lower limit or select a single thread; original report remains unchanged.");
                    }
                    result.addProperty("inputSource", arguments.has("file") ? "local-json-import" : "official-spark-full-json");
                    if (active.getAsBoolean() && !worker.isShutdown()) success.accept(result);
                } catch (Throwable error) {
                    if (active.getAsBoolean() && !worker.isShutdown()) failure.accept(error);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new SparkException("spark_busy", "The Spark report worker is busy or shutting down (one active report, four queued maximum). Retry after an existing request completes.");
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new SparkException("spark_disabled", "Spark integration is disabled in config.yml.");
    }

    @Override public void close() { worker.shutdownNow(); }
}
