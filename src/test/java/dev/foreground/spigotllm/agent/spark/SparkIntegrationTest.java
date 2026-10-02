package dev.foreground.spigotllm.agent.spark;

import com.google.gson.JsonObject;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimpleServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.foreground.spigotllm.agent.spark.SparkReportAnalyzerTest.json;

class SparkIntegrationTest {
    @TempDir Path directory;
    private final AtomicInteger dispatches = new AtomicInteger();
    private final AtomicReference<String> lastCommand = new AtomicReference<String>();

    private Server server(boolean accept) {
        SimpleServicesManager services = new SimpleServicesManager();
        PluginManager plugins = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> { if (method.getName().equals("getPlugin")) return null; throw new UnsupportedOperationException(method.getName()); });
        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ConsoleCommandSender.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        return (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getServicesManager": return services;
                case "getPluginManager": return plugins;
                case "getConsoleSender": return console;
                case "dispatchCommand": dispatches.incrementAndGet(); lastCommand.set((String) args[1]); return accept;
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    private SparkIntegration integration(boolean enabled, boolean accept) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("spark.enabled", enabled);
        config.set("spark.allow-remote-reports", false);
        Files.write(directory.resolve("profile.json"), SparkReportAnalyzerTest.modern().toString().getBytes(StandardCharsets.UTF_8));
        return new SparkIntegration(server(accept), directory.resolve("latest.log"), directory, config);
    }

    @Test void startsWithoutSparkAndReportsMissingServiceRatherThanFailingPluginLoad() throws Exception {
        try (SparkIntegration spark = integration(true, false)) {
            JsonObject status = spark.status();
            assertFalse(status.get("apiAvailable").getAsBoolean());
            assertFalse(status.get("standalonePluginEnabled").getAsBoolean());
            assertFalse(status.get("forkRequired").getAsBoolean());
            assertEquals(4, status.getAsJsonArray("operations").size());
            assertEquals("spark_unavailable", assertThrows(SparkException.class, spark::snapshot).getCode());
        }
    }

    @Test void dispatchReceiptsNeverClaimSparkCompletedAndShutdownDoesNotCancelSharedState() throws Exception {
        try (SparkIntegration spark = integration(true, true)) {
            JsonObject result = spark.command(json("{\"command\":\"profiler start\",\"confirm\":true}"));
            assertEquals("spark profiler start --timeout 60", lastCommand.get());
            assertEquals("dispatched", result.get("status").getAsString());
            assertFalse(result.get("completed").getAsBoolean());
            assertTrue(result.get("dispatchedAtMillis").getAsLong() > 0);
            assertEquals("spark_confirmation_required", assertThrows(SparkException.class, () -> spark.command(json("{\"command\":\"profiler cancel\"}"))).getCode());
        }
        assertEquals(1, dispatches.get(), "Closing a lease/plugin must not blindly cancel a shared Spark profiler.");
        try (SparkIntegration spark = integration(true, false)) {
            assertEquals("rejected", spark.command(json("{\"command\":\"profiler info\"}")).get("status").getAsString());
        }
    }

    @Test void disabledIntegrationKeepsStatusButRejectsOperations() throws Exception {
        try (SparkIntegration spark = integration(false, true)) {
            assertFalse(spark.status().get("enabled").getAsBoolean());
            assertEquals("spark_disabled", assertThrows(SparkException.class, spark::snapshot).getCode());
            assertThrows(SparkException.class, () -> spark.command(json("{\"command\":\"tps\"}")));
            assertThrows(SparkException.class, () -> spark.report(json("{\"file\":\"profile.json\"}"), () -> true, out -> {}, err -> {}));
        }
        assertEquals(0, dispatches.get());
    }

    @Test void loadsAndAnalyzesOffThreadWithoutSparkInstalled() throws Exception {
        try (SparkIntegration spark = integration(true, false)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<JsonObject> result = new AtomicReference<JsonObject>();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            AtomicReference<String> thread = new AtomicReference<String>();
            spark.report(json("{\"file\":\"profile.json\"}"), () -> true,
                    out -> { result.set(out); thread.set(Thread.currentThread().getName()); done.countDown(); },
                    err -> { failure.set(err); done.countDown(); });
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(300, result.get().get("totalWeight").getAsDouble());
            assertEquals("SpigotLLM-spark-reports", thread.get());
        }
    }

    @Test void closedLeasesCannotReadOrReceiveQueuedReports() throws Exception {
        try (SparkIntegration spark = integration(true, false)) {
            AtomicInteger staleCallbacks = new AtomicInteger();
            CountDownLatch barrier = new CountDownLatch(1);
            spark.report(json("{\"file\":\"missing.json\"}"), () -> false,
                    out -> staleCallbacks.incrementAndGet(), err -> staleCallbacks.incrementAndGet());
            spark.report(json("{\"file\":\"profile.json\"}"), () -> true,
                    out -> barrier.countDown(), err -> barrier.countDown());
            assertTrue(barrier.await(5, TimeUnit.SECONDS));
            assertEquals(0, staleCallbacks.get());
        }
    }

    @Test void reportsAreBoundedToOneWorkerAndFourQueuedRequests() throws Exception {
        try (SparkIntegration spark = integration(true, false)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(5);
            AtomicInteger failures = new AtomicInteger();
            JsonObject request = json("{\"file\":\"profile.json\"}");
            spark.report(request, () -> {
                entered.countDown();
                try { return release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }, out -> done.countDown(), err -> { failures.incrementAndGet(); done.countDown(); });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 4; i++) spark.report(request, () -> true, out -> done.countDown(), err -> { failures.incrementAndGet(); done.countDown(); });
                assertEquals(4, spark.status().get("reportQueueDepth").getAsInt());
                assertEquals("spark_busy", assertThrows(SparkException.class, () -> spark.report(request, () -> true, out -> {}, err -> {})).getCode());
            } finally { release.countDown(); }
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(0, failures.get());
        }
    }
}
