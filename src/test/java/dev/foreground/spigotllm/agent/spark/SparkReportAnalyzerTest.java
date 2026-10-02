package dev.foreground.spigotllm.agent.spark;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class SparkReportAnalyzerTest {
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static JsonObject analyze(JsonObject input, JsonObject args) { return new SparkReportAnalyzer().analyze(input, args); }
    static JsonObject analyze(JsonObject input) { return analyze(input, new JsonObject()); }
    static JsonObject row(JsonObject output, String key, String name) {
        for (JsonElement value : output.getAsJsonArray(key)) {
            if (name.equals(value.getAsJsonObject().get("name").getAsString())) return value.getAsJsonObject();
        }
        fail("Missing row " + name + " in " + key); return null;
    }

    static JsonObject modern() {
        return json("{\"type\":\"sampler\",\"metadata\":{\"samplerMode\":0,\"startTime\":1000,\"endTime\":2000},"
                + "\"timeWindows\":[100,101],\"classSources\":{\"example.Plugin\":\"Example\"},"
                + "\"timeWindowStatistics\":{\"100\":{\"msptMax\":40,\"tps\":20},\"101\":{\"msptMax\":120,\"tps\":18}},"
                + "\"threads\":[{\"name\":\"Server thread\",\"times\":[100,200],\"childrenRefs\":[0],\"children\":["
                + "{\"className\":\"server.Tick\",\"methodName\":\"run\",\"times\":[100,200],\"childrenRefs\":[1,2]},"
                + "{\"className\":\"example.Plugin\",\"methodName\":\"work\",\"times\":[60,140],\"childrenRefs\":[3]},"
                + "{\"className\":\"java.lang.Thread\",\"methodName\":\"sleep\",\"times\":[40,60],\"childrenRefs\":[]},"
                + "{\"className\":\"java.util.Map\",\"methodName\":\"get\",\"times\":[20,40],\"childrenRefs\":[]}]}]}");
    }
    static JsonObject thread(JsonObject input) { return input.getAsJsonArray("threads").get(0).getAsJsonObject(); }
    static JsonObject frame(JsonObject input, int index) { return thread(input).getAsJsonArray("children").get(index).getAsJsonObject(); }

    @Test void analyzesReferenceArenaWithoutTreatingAllArenaEntriesAsRoots() {
        JsonObject out = analyze(modern());
        assertEquals(300, out.get("totalWeight").getAsDouble());
        assertEquals(5, out.get("visitedNodes").getAsInt());
        assertEquals(140, row(out, "topSelf", "example.Plugin.work").get("selfWeight").getAsDouble());
        assertEquals(200, row(out, "topInclusive", "example.Plugin.work").get("inclusiveWeight").getAsDouble());
        assertEquals(140.0 / 300 * 100, row(out, "topSelf", "example.Plugin.work").get("selfPercent").getAsDouble(), 0.00001);
        assertEquals(200, row(out, "sourceSelf", "Example").get("selfWeight").getAsDouble());
        assertEquals(100, row(out, "sourceSelf", "unknown").get("selfWeight").getAsDouble());
        assertEquals("sampled_milliseconds", out.get("weightUnit").getAsString());
        assertTrue(out.getAsJsonArray("hotPaths").size() > 0);
        assertEquals(101, out.getAsJsonArray("worstTickWindows").get(0).getAsJsonObject().get("windowId").getAsInt());
    }

    @Test void filtersHalfOpenWindowRangeAndKeepsWholeSeriesScopeExplicit() {
        JsonObject input = modern();
        input.getAsJsonObject("metadata").add("metrics", json("{\"tps\":{\"startTimestampMs\":1000,\"values\":[20,18]}}"));
        JsonObject out = analyze(input, json("{\"windowStart\":1,\"windowEnd\":2}"));
        assertEquals(200, out.get("totalWeight").getAsDouble());
        assertEquals(100, row(out, "topSelf", "example.Plugin.work").get("selfWeight").getAsDouble());
        assertEquals(50, row(out, "topSelf", "example.Plugin.work").get("selfPercent").getAsDouble());
        assertEquals(1, out.getAsJsonArray("worstTickWindows").size());
        assertTrue(out.getAsJsonObject("metricSeries").getAsJsonObject("tps").get("scope").getAsString().contains("Whole exported"));
    }

    @Test void filtersExactThreadRatherThanSubstring() {
        JsonObject input = modern();
        input.getAsJsonArray("threads").add(json("{\"name\":\"Server thread worker\",\"times\":[50,50],\"children\":[]}"));
        assertEquals(400, analyze(input).get("totalWeight").getAsDouble());
        assertEquals(300, analyze(input, json("{\"thread\":\"Server thread\"}")).get("totalWeight").getAsDouble());
        assertThrows(SparkException.class, () -> analyze(input, json("{\"thread\":\"Server\"}")));
    }

    @Test void deduplicatesRecursiveInclusiveWeightButAddsEverySelfWeight() {
        JsonObject input = json("{\"type\":\"sampler\",\"metadata\":{},\"threads\":[{\"name\":\"main\",\"time\":100,\"children\":["
                + "{\"className\":\"A\",\"methodName\":\"run\",\"time\":100,\"children\":["
                + "{\"className\":\"A\",\"methodName\":\"run\",\"time\":60,\"children\":["
                + "{\"className\":\"B\",\"methodName\":\"leaf\",\"time\":20}]}]}]}]}");
        JsonObject out = analyze(input);
        assertEquals(100, row(out, "topInclusive", "A.run").get("inclusiveWeight").getAsDouble());
        assertEquals(80, row(out, "topSelf", "A.run").get("selfWeight").getAsDouble());
        assertEquals(20, row(out, "topSelf", "B.leaf").get("selfWeight").getAsDouble());
    }

    @Test void respectsExplicitLineThenMethodThenClassSourceAttribution() {
        JsonObject input = modern();
        input.add("methodSources", json("{\"example.Plugin;work;\":\"MethodSource\"}"));
        assertEquals(200, row(analyze(input), "sourceSelf", "MethodSource").get("selfWeight").getAsDouble());
        frame(input, 1).addProperty("lineNumber", 42);
        input.add("lineSources", json("{\"example.Plugin;42\":\"LineSource\"}"));
        assertEquals(200, row(analyze(input), "sourceSelf", "LineSource").get("selfWeight").getAsDouble());
    }

    @Test void allocationUsesBytesNotMilliseconds() {
        JsonObject input = modern();
        input.getAsJsonObject("metadata").addProperty("samplerMode", "ALLOCATION");
        JsonObject out = analyze(input);
        assertEquals("sampled_bytes", out.get("weightUnit").getAsString());
        assertTrue(out.get("warnings").toString().contains("not retained"));
    }

    @Test void metadataOnlyIsNotClaimedToContainHotspots() {
        JsonObject out = analyze(json("{\"type\":\"sampler\",\"metadata\":{\"interval\":4000}}"));
        assertFalse(out.get("dataAvailable").getAsBoolean());
        assertEquals(0, out.getAsJsonArray("topSelf").size());
        assertTrue(out.get("warnings").toString().contains("Metadata-only"));
    }

    @Test void zeroWeightHasNullPercentages() {
        JsonObject input = json("{\"type\":\"sampler\",\"metadata\":{},\"threads\":[{\"name\":\"empty\",\"time\":0,\"children\":[{\"className\":\"A\",\"methodName\":\"run\",\"time\":0}]}]}");
        JsonObject out = analyze(input);
        assertTrue(row(out, "topSelf", "A.run").get("selfPercent").isJsonNull());
        assertFalse(out.get("dataAvailable").getAsBoolean());
    }

    @Test void reportsTruncatedRankingsExplicitly() {
        JsonObject out = analyze(modern(), json("{\"limit\":1}"));
        assertEquals(1, out.getAsJsonArray("topSelf").size());
        assertTrue(out.get("methodRankingsTruncated").getAsBoolean());
        assertEquals(4, out.get("methodCount").getAsInt());
    }

    @Test void redactsSensitiveMetadataButKeepsHealthEvidence() {
        JsonObject input = modern();
        input.getAsJsonObject("metadata").add("user", json("{\"name\":\"SECRET_CREATOR\"}"));
        input.getAsJsonObject("metadata").addProperty("comment", "SECRET_COMMENT");
        input.getAsJsonObject("metadata").add("serverConfigurations", json("{\"token\":\"SECRET_TOKEN\"}"));
        input.getAsJsonObject("metadata").add("systemStatistics", json("{\"java\":{\"version\":\"21\",\"vmArgs\":\"SECRET_VM\"},\"cpu\":{\"threads\":8}}"));
        input.add("channelInfo", json("{\"publicKey\":\"SECRET_KEY\"}"));
        String out = analyze(input).toString();
        assertFalse(out.contains("SECRET_"));
        assertTrue(out.contains("\"threads\":8"));
        assertTrue(out.contains("\"version\":\"21\""));
    }

    @Test void rejectsInvalidReferences() {
        for (String refs : new String[]{"[-1]", "[100]", "[0.5]"}) {
            JsonObject input = modern();
            thread(input).add("childrenRefs", JsonParser.parseString(refs));
            assertThrows(SparkException.class, () -> analyze(input));
        }
    }

    @Test void rejectsCyclesSharedChildrenAndUnreachableNodes() {
        JsonObject cycle = modern(); frame(cycle, 3).add("childrenRefs", JsonParser.parseString("[0]"));
        assertThrows(SparkException.class, () -> analyze(cycle));
        JsonObject shared = modern(); frame(shared, 2).add("childrenRefs", JsonParser.parseString("[3]"));
        assertThrows(SparkException.class, () -> analyze(shared));
        JsonObject orphan = modern(); thread(orphan).getAsJsonArray("children").add(json("{\"className\":\"Orphan\",\"times\":[0,0]}"));
        assertThrows(SparkException.class, () -> analyze(orphan));
    }

    @Test void rejectsNegativeOverflowingAndInconsistentWeights() {
        JsonObject negative = modern(); frame(negative, 1).add("times", JsonParser.parseString("[-1,2]"));
        assertThrows(SparkException.class, () -> analyze(negative));
        JsonObject tooBig = modern(); frame(tooBig, 1).add("times", JsonParser.parseString("[1000,2000]"));
        assertThrows(SparkException.class, () -> analyze(tooBig));
        JsonObject overflow = modern(); thread(overflow).add("times", JsonParser.parseString("[1e308,1e308]"));
        assertThrows(SparkException.class, () -> analyze(overflow));
    }

    @Test void rejectsBadWindowAlignmentAndInvalidSelections() {
        JsonObject missing = modern(); frame(missing, 1).add("times", JsonParser.parseString("[1]"));
        assertThrows(SparkException.class, () -> analyze(missing));
        for (String args : new String[]{"{\"windowStart\":2,\"windowEnd\":1}", "{\"windowEnd\":3}", "{\"windowStart\":1.5}", "{\"limit\":0}"}) {
            assertThrows(SparkException.class, () -> analyze(modern(), json(args)));
        }
        JsonObject legacy = json("{\"type\":\"sampler\",\"metadata\":{},\"timeWindows\":[1],\"threads\":[{\"name\":\"main\",\"time\":4}]}");
        assertThrows(SparkException.class, () -> analyze(legacy, json("{\"windowEnd\":1}")));
    }

    @Test void rejectsUnknownModeAndNonSparkObjects() {
        JsonObject unknown = modern(); unknown.getAsJsonObject("metadata").addProperty("samplerMode", 7);
        assertThrows(SparkException.class, () -> analyze(unknown));
        assertThrows(SparkException.class, () -> analyze(json("{}")));
    }

    @Test void handlesDeepReferenceTreesWithoutJavaRecursion() {
        JsonObject input = json("{\"type\":\"sampler\",\"metadata\":{},\"threads\":[{\"name\":\"deep\",\"time\":1,\"childrenRefs\":[0],\"children\":[]}]} ");
        JsonArray arena = thread(input).getAsJsonArray("children");
        for (int i = 0; i < 1000; i++) {
            JsonObject node = json("{\"className\":\"Deep\",\"methodName\":\"run\",\"time\":1}");
            if (i < 999) node.add("childrenRefs", JsonParser.parseString("[" + (i + 1) + "]"));
            arena.add(node);
        }
        JsonObject out = analyze(input);
        assertEquals(1, row(out, "topInclusive", "Deep.run").get("inclusiveWeight").getAsDouble());
        assertEquals(968, out.getAsJsonArray("hotPaths").get(0).getAsJsonObject().get("leadingFramesOmitted").getAsInt());
    }

    @Test void heapRanksBytesAndCountsSeparately() {
        JsonObject out = analyze(json("{\"type\":\"heap\",\"metadata\":{},\"entries\":[{\"type\":\"A\",\"size\":1024,\"instances\":8},{\"type\":\"B\",\"size\":64,\"instances\":16}]}"));
        assertEquals(1088, out.get("totalBytes").getAsLong());
        assertEquals(24, out.get("totalInstances").getAsLong());
        assertEquals("A", out.getAsJsonArray("topByBytes").get(0).getAsJsonObject().get("class").getAsString());
        assertEquals("B", out.getAsJsonArray("topByInstances").get(0).getAsJsonObject().get("class").getAsString());
        assertEquals(128, out.getAsJsonArray("topByBytes").get(0).getAsJsonObject().get("averageBytesPerInstance").getAsDouble());
    }

    @Test void heapPreserves64BitIntegerPrecisionAndRejectsOverflow() {
        JsonObject input = json("{\"type\":\"heap\",\"metadata\":{},\"entries\":[{\"type\":\"A\",\"size\":\"9007199254740993\",\"instances\":1}]} ");
        assertEquals(9007199254740993L, analyze(input).get("totalBytes").getAsLong());
        input.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("size", Long.MAX_VALUE);
        input.getAsJsonArray("entries").add(json("{\"type\":\"B\",\"size\":1,\"instances\":1}"));
        assertThrows(SparkException.class, () -> analyze(input));
    }

    @Test void healthHasNoInventedCallTreeAndRejectsProfilerFilters() {
        JsonObject input = json("{\"type\":\"health\",\"metadata\":{\"platformStatistics\":{\"tps\":{\"last1m\":19.5}}}} ");
        JsonObject out = analyze(input);
        assertFalse(out.has("topSelf"));
        assertTrue(out.get("dataAvailable").getAsBoolean());
        assertThrows(SparkException.class, () -> analyze(input, json("{\"thread\":\"main\"}")));
    }

    @Test void strictParserRejectsDuplicateKeysTrailingDataAndExcessiveDepth() {
        assertThrows(SparkException.class, () -> SparkJson.parse(new StringReader("{\"type\":1,\"type\":2}")));
        assertThrows(Exception.class, () -> SparkJson.parse(new StringReader("{} {}")));
        StringBuilder json = new StringBuilder("{\"x\":");
        for (int i = 0; i < 70; i++) json.append('[');
        json.append('0'); for (int i = 0; i < 70; i++) json.append(']'); json.append('}');
        assertThrows(SparkException.class, () -> SparkJson.parse(new StringReader(json.toString())));
        assertThrows(SparkException.class, () -> SparkJson.parse(new StringReader("{\"x\":1e999}")));
    }
}
