package dev.foreground.spigotllm.agent.spark;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Public API contract fixture, deliberately loaded outside SpigotLLM's class loader.
 * It is not a simulated running profiler. Non-public implementations ensure that
 * the adapter invokes public contracts, rather than implementation-class methods.
 */
class SparkApiAccessTest {
    @TempDir Path directory;

    @Test void pollsPublicContractsAcrossClassLoadersWithPartialUnsupportedStatistics() throws Exception {
        try (URLClassLoader loader = fixture(true)) {
            Class<?> api = loader.loadClass(SparkApiAccess.API);
            Object provider = loader.loadClass("me.lucko.spark.api.Fixture").getMethod("create").invoke(null);
            JsonObject result = new SparkApiAccess(api, provider).snapshot();
            JsonObject statistics = result.getAsJsonObject("statistics");
            assertTrue(statistics.getAsJsonObject("cpuProcess").get("available").getAsBoolean());
            assertEquals(0.25, statistics.getAsJsonObject("cpuProcess").getAsJsonObject("windows").get("MINUTES_1").getAsDouble());
            assertTrue(statistics.getAsJsonObject("cpuProcess").getAsJsonObject("windows").get("MINUTES_15").isJsonNull());
            assertEquals(42, statistics.getAsJsonObject("mspt").getAsJsonObject("windows").getAsJsonObject("MINUTES_1").get("median").getAsDouble());
            assertFalse(statistics.getAsJsonObject("tps").get("available").getAsBoolean());
            assertFalse(statistics.getAsJsonObject("memoryAllocation").get("available").getAsBoolean());
            assertEquals("UnsupportedOperationException", statistics.getAsJsonObject("playerPing").get("reason").getAsString());
            assertTrue(result.get("gcAvailable").getAsBoolean());
            assertEquals(35, result.getAsJsonArray("gc").get(0).getAsJsonObject().get("totalTime").getAsLong());
            assertEquals("milliseconds", result.get("gcDurationUnit").getAsString());
        }
    }

    @Test void olderApiMissingAMethodDoesNotBreakOtherMetrics() throws Exception {
        try (URLClassLoader loader = fixture(false)) {
            Class<?> api = loader.loadClass(SparkApiAccess.API);
            Object provider = loader.loadClass("me.lucko.spark.api.Fixture").getMethod("create").invoke(null);
            JsonObject statistics = new SparkApiAccess(api, provider).snapshot().getAsJsonObject("statistics");
            assertEquals("NoSuchMethodException", statistics.getAsJsonObject("memoryAllocation").get("reason").getAsString());
            assertTrue(statistics.getAsJsonObject("cpuSystem").get("available").getAsBoolean());
        }
    }

    private URLClassLoader fixture(boolean allocationMethod) throws Exception {
        Map<String, String> sources = new LinkedHashMap<String, String>();
        sources.put("statistic.Statistic", "public interface Statistic { Enum<?>[] getWindows(); }");
        sources.put("statistic.types.DoubleStatistic", "public interface DoubleStatistic extends me.lucko.spark.api.statistic.Statistic { double[] poll(); }");
        sources.put("statistic.types.GenericStatistic", "public interface GenericStatistic extends me.lucko.spark.api.statistic.Statistic { Object[] poll(); }");
        sources.put("statistic.misc.DoubleAverageInfo", "public interface DoubleAverageInfo { double mean(); double min(); double max(); double median(); double percentile95th(); }");
        sources.put("gc.GarbageCollector", "public interface GarbageCollector { long totalCollections(); long totalTime(); double avgTime(); double avgFrequency(); }");
        sources.put("Spark", "import me.lucko.spark.api.statistic.types.*; public interface Spark { DoubleStatistic cpuProcess(); DoubleStatistic cpuSystem(); DoubleStatistic tps(); GenericStatistic mspt(); GenericStatistic playerPing(); "
                + (allocationMethod ? "GenericStatistic memoryAllocation(); " : "") + "java.util.Map<String,me.lucko.spark.api.gc.GarbageCollector> gc(); }");
        sources.put("Fixture", "import me.lucko.spark.api.statistic.types.*; import me.lucko.spark.api.statistic.misc.*; import me.lucko.spark.api.gc.*; import java.util.*; "
                + "public final class Fixture { public static Spark create() { return new Hidden(); } enum W { MINUTES_1, MINUTES_15 } "
                + "private static class D implements DoubleStatistic { public Enum<?>[] getWindows(){return W.values();} public double[] poll(){return new double[]{0.25,Double.NaN};} } "
                + "private static class A implements DoubleAverageInfo { public double mean(){return 40;} public double min(){return 20;} public double max(){return 80;} public double median(){return 42;} public double percentile95th(){return 75;} } "
                + "private static class G implements GenericStatistic { public Enum<?>[] getWindows(){return new W[]{W.MINUTES_1};} public Object[] poll(){return new Object[]{new A()};} } "
                + "private static class C implements GarbageCollector { public long totalCollections(){return 7;} public long totalTime(){return 35;} public double avgTime(){return 5;} public double avgFrequency(){return 1000;} } "
                + "private static class Hidden implements Spark { public DoubleStatistic cpuProcess(){return new D();} public DoubleStatistic cpuSystem(){return new D();} public DoubleStatistic tps(){return null;} public GenericStatistic mspt(){return new G();} public GenericStatistic memoryAllocation(){return null;} public GenericStatistic playerPing(){throw new UnsupportedOperationException();} public Map<String,GarbageCollector> gc(){return Collections.<String,GarbageCollector>singletonMap(\"collector\",new C());} } }");
        List<String> args = new ArrayList<String>(Arrays.asList("-source", "8", "-target", "8", "-d", directory.toString()));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String name = "me.lucko.spark.api." + entry.getKey();
            Path file = directory.resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            String packageName = name.substring(0, name.lastIndexOf('.'));
            Files.write(file, ("package " + packageName + "; " + entry.getValue()).getBytes(StandardCharsets.UTF_8));
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Run tests with a JDK, not a JRE.");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, errors, errors, args.toArray(new String[0])), errors.toString("UTF-8"));
        return new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader());
    }
}
