package dev.foreground.spigotllm.agent.spark;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static dev.foreground.spigotllm.agent.spark.SparkReportAnalyzerTest.json;

class SparkTransportAndPolicyTest {
    @TempDir Path directory;

    @Test void canonicalizesOfficialViewerLinksAndCodesToFullExports() {
        String expected = "https://spark.lucko.me/Abc_123-xyz?raw=1&full=true";
        assertEquals(expected, SparkReportSource.canonicalUrl("Abc_123-xyz").toString());
        assertEquals(expected, SparkReportSource.canonicalUrl("https://spark.lucko.me/Abc_123-xyz?raw=0&path=metadata#bookmark").toString());
        assertEquals(expected, SparkReportSource.canonicalUrl("https://spark.lucko.me:443/Abc_123-xyz/").toString());
    }

    @Test void rejectsUntrustedHostsSchemesAuthoritiesAndPaths() {
        for (String input : new String[]{"http://spark.lucko.me/code", "https://127.0.0.1/code", "file:///etc/passwd", "https://spark.lucko.me.evil.test/code",
                "https://evil.test@spark.lucko.me/code", "https://spark.lucko.me:8443/code", "https://spark.lucko.me/a/b", "https://spark.lucko.me/../code",
                "https://spark.lucko.me/a%2Fb", "https://spark.lucko.me/%2e%2e", "https://spark.lucko.me/", "", "https://spark.lucko.me\\@evil.test/code"}) {
            assertThrows(SparkException.class, () -> SparkReportSource.canonicalUrl(input), input);
        }
    }

    @Test void localJsonImportsNeedNoSparkPluginOrInternet() throws Exception {
        SparkReportSource source = new SparkReportSource(directory, false, 16384, 1000);
        Files.write(directory.resolve("profile.json"), SparkReportAnalyzerTest.modern().toString().getBytes(StandardCharsets.UTF_8));
        JsonObject input = source.load(json("{\"file\":\"profile.json\"}"));
        assertEquals("sampler", input.get("type").getAsString());
        assertEquals(300, SparkReportAnalyzerTest.analyze(input).get("totalWeight").getAsDouble());
    }

    @Test void confinesFilesAndRejectsBinaryFormatsAndAmbiguousSources() throws Exception {
        SparkReportSource source = new SparkReportSource(directory, false, 1024, 1000);
        for (String path : new String[]{"../outside.json", directory.resolve("absolute.json").toString(), "capture.sparkprofile", "dump.hprof"}) {
            assertThrows(SparkException.class, () -> source.resolveFile(path));
        }
        assertThrows(SparkException.class, () -> source.load(json("{}")));
        assertThrows(SparkException.class, () -> source.load(json("{\"url\":\"abc\",\"file\":\"test.json\"}")));
        assertEquals("spark_remote_disabled", assertThrows(SparkException.class, () -> source.load(json("{\"url\":\"abc\"}"))).getCode());
    }

    @Test void appliesFileAndStreamingByteLimits() throws Exception {
        assertEquals(4, SparkReportSource.readBounded(new ByteArrayInputStream(new byte[4]), 4).length);
        assertEquals("spark_report_too_large", assertThrows(SparkException.class, () -> SparkReportSource.readBounded(new ByteArrayInputStream(new byte[5]), 4)).getCode());
        SparkReportSource source = new SparkReportSource(directory, false, 1024, 1000);
        Files.write(directory.resolve("big.json"), new byte[1025]);
        assertEquals("spark_report_too_large", assertThrows(SparkException.class, () -> source.load(json("{\"file\":\"big.json\"}"))).getCode());
    }

    @Test void malformedImportsAreStableProtocolErrors() throws Exception {
        SparkReportSource source = new SparkReportSource(directory, false, 1024, 1000);
        Files.write(directory.resolve("bad.json"), "<html>not json</html>".getBytes(StandardCharsets.UTF_8));
        assertEquals("spark_invalid_data", assertThrows(SparkException.class, () -> source.load(json("{\"file\":\"bad.json\"}"))).getCode());
    }

    @Test void readOnlyCommandsDoNotRequireConfirmation() {
        for (String command : new String[]{"profiler info", "tps", "cpu", "gc", "ping --player Alice", "activity --page 2", "health show --memory --network"}) {
            JsonObject args = new JsonObject(); args.addProperty("command", command);
            assertEquals("spark " + command, SparkCommandPolicy.build(args, 300));
        }
    }

    @Test void everyStatefulOrUploadingCommandRequiresExplicitConfirmation() {
        for (String command : new String[]{"profiler start", "profiler stop", "profiler cancel", "profiler open", "health", "health upload", "healthreport",
                "health dashboard", "heapsummary", "heapsummary --save-to-file", "heapdump", "heapdump --compress gzip --include-non-live", "gcmonitor", "tickmonitor --threshold-tick 50"}) {
            JsonObject args = new JsonObject(); args.addProperty("command", command);
            assertEquals("spark_confirmation_required", assertThrows(SparkException.class, () -> SparkCommandPolicy.build(args, 300), command).getCode());
            args.addProperty("confirm", true);
            assertTrue(SparkCommandPolicy.build(args, 300).startsWith("spark "));
        }
    }

    @Test void profilerStartAlwaysGetsBoundedTimeout() {
        assertEquals("spark profiler start --timeout 60", SparkCommandPolicy.build(json("{\"command\":\"profiler start\",\"confirm\":true}"), 300));
        assertEquals("spark profiler start --timeout 20", SparkCommandPolicy.build(json("{\"command\":\"profiler start\",\"confirm\":true}"), 20));
        for (String timeout : new String[]{"0", "10", "-1", "301", "1.5", "999999999999"}) {
            JsonObject args = json("{\"confirm\":true}"); args.addProperty("command", "profiler start --timeout " + timeout);
            assertThrows(SparkException.class, () -> SparkCommandPolicy.build(args, 300));
        }
    }

    @Test void rejectsUnsafeCommandShapesAndConfirmationBypasses() {
        for (String command : new String[]{"stop", "spark profiler start", "/spark profiler start", "profiler", "profiler info --start", "profiler --start",
                "profiler start\nstop", "profiler start --timeout 30 --timeout 3000", "gcmonitor --stop", "health show --upload", "tps; stop"}) {
            JsonObject args = json("{\"confirm\":true}"); args.addProperty("command", command);
            assertThrows(SparkException.class, () -> SparkCommandPolicy.build(args, 300), command);
        }
        assertThrows(SparkException.class, () -> SparkCommandPolicy.build(json("{\"command\":\"profiler start\",\"confirm\":\"true\"}"), 300));
    }

    @Test void validatesSamplingModeSpecificIntervalsAndThreadFlags() {
        assertEquals("spark profiler start --thread Server thread --only-ticks-over 50 --timeout 60",
                SparkCommandPolicy.build(json("{\"command\":\"profiler start --thread Server thread --only-ticks-over 50\",\"confirm\":true}"), 300));
        assertTrue(SparkCommandPolicy.build(json("{\"command\":\"profiler start --alloc --interval 524287\",\"confirm\":true}"), 300).contains("--alloc"));
        for (String options : new String[]{"--interval NaN", "--interval 0.01", "--interval 2000", "--alloc --interval 4", "--alloc-live-only", "--alloc --force-java-sampler",
                "--combine-all --not-combined", "--timeout", "--only-ticks-over nope"}) {
            JsonObject args = json("{\"confirm\":true}"); args.addProperty("command", "profiler start " + options);
            assertThrows(SparkException.class, () -> SparkCommandPolicy.build(args, 300));
        }
    }

    @Test void finiteApiReadingsDoNotTurnUnavailableValuesIntoZero() {
        assertTrue(SparkApiAccess.finite(Double.NaN).isJsonNull());
        assertTrue(SparkApiAccess.finite(Double.POSITIVE_INFINITY).isJsonNull());
        assertTrue(SparkApiAccess.finite(-1L).isJsonNull());
        assertTrue(SparkApiAccess.finite(null).isJsonNull());
        assertEquals(0.25, SparkApiAccess.finite(0.25).getAsDouble());
    }
}
