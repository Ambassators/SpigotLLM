package dev.foreground.spigotllm.agent.spark;

import com.google.gson.*;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/** Optional binding to public Spark API interfaces in Spark's own class loader.
 * No Spark classes are bundled, and no implementation/private members are used.
 */
final class SparkApiAccess {
    static final String API = "me.lucko.spark.api.Spark";
    private final Class<?> apiType;
    private final Object provider;

    SparkApiAccess(Class<?> apiType, Object provider) {
        this.apiType = apiType;
        this.provider = provider;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static SparkApiAccess find(Server server) {
        for (Class<?> service : server.getServicesManager().getKnownServices()) {
            if (!API.equals(service.getName())) continue;
            RegisteredServiceProvider<?> registration = server.getServicesManager().getRegistration((Class) service);
            if (registration != null && registration.getProvider() != null) {
                return new SparkApiAccess(service, registration.getProvider());
            }
        }
        return null;
    }

    JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.addProperty("source", "spark-public-api");
        result.addProperty("capturedAtMillis", System.currentTimeMillis());
        JsonObject statistics = new JsonObject();
        result.add("statistics", statistics);
        JsonArray warnings = new JsonArray();
        result.add("warnings", warnings);
        metric(statistics, warnings, "cpuProcess", "ratio_0_to_1", false);
        metric(statistics, warnings, "cpuSystem", "ratio_0_to_1", false);
        metric(statistics, warnings, "tps", "ticks_per_second", false);
        metric(statistics, warnings, "mspt", "milliseconds", true);
        metric(statistics, warnings, "memoryAllocation", "bytes_per_second", true);
        metric(statistics, warnings, "playerPing", "milliseconds", true);
        JsonArray collectors = new JsonArray();
        result.add("gc", collectors);
        try {
            Object raw = apiType.getMethod("gc").invoke(provider);
            if (!(raw instanceof Map)) throw new IllegalStateException("No GC map returned.");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (collectors.size() >= 32) { warnings.add("GC collector list limited to 32."); break; }
                JsonObject item = new JsonObject();
                item.addProperty("name", SparkJson.label(String.valueOf(entry.getKey())));
                for (String field : new String[]{"totalCollections", "totalTime", "avgTime", "avgFrequency"}) {
                    item.add(field, finite(call("gc.GarbageCollector", entry.getValue(), field)));
                }
                collectors.add(item);
            }
            result.addProperty("gcAvailable", true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            result.addProperty("gcAvailable", false);
            warnings.add("gc unavailable: " + reason(failure));
        }
        result.addProperty("gcDurationUnit", "milliseconds");
        result.addProperty("interpretation", "Rolling windows are not a profile. Null readings are unavailable, not zero. GC totals are cumulative; use deltas for a measurement interval.");
        return result;
    }

    private void metric(JsonObject result, JsonArray warnings, String name, String unit, boolean average) {
        JsonObject metric = new JsonObject();
        result.add(name, metric);
        metric.addProperty("unit", unit);
        try {
            Object statistic = apiType.getMethod(name).invoke(provider);
            if (statistic == null) {
                metric.addProperty("available", false);
                metric.addProperty("reason", "Not supported by this Spark platform/version.");
                return;
            }
            Object windows = call("statistic.Statistic", statistic, "getWindows");
            Object values = call(average ? "statistic.types.GenericStatistic" : "statistic.types.DoubleStatistic", statistic, "poll");
            if (Array.getLength(windows) != Array.getLength(values) || Array.getLength(windows) > 32) {
                throw new IllegalStateException("Unexpected statistic window count.");
            }
            JsonObject readings = new JsonObject();
            for (int i = 0; i < Array.getLength(windows); i++) {
                Object value = Array.get(values, i);
                String window = ((Enum<?>) Array.get(windows, i)).name();
                if (!average || value == null) readings.add(window, finite(value));
                else {
                    JsonObject summary = new JsonObject();
                    for (String field : new String[]{"mean", "min", "max", "median", "percentile95th"}) {
                        summary.add(field, finite(call("statistic.misc.DoubleAverageInfo", value, field)));
                    }
                    readings.add(window, summary);
                }
            }
            metric.addProperty("available", true);
            metric.add("windows", readings);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            metric.addProperty("available", false);
            metric.addProperty("reason", reason(failure));
            warnings.add(name + " unavailable; other statistics may still be usable.");
        }
    }

    private Object call(String type, Object receiver, String method) throws ReflectiveOperationException {
        Class<?> contract = Class.forName("me.lucko.spark.api." + type, false, apiType.getClassLoader());
        return contract.getMethod(method).invoke(receiver);
    }

    static JsonElement finite(Object value) {
        if (!(value instanceof Number)) return JsonNull.INSTANCE;
        Number number = (Number) value;
        return Double.isFinite(number.doubleValue()) && number.doubleValue() >= 0
                ? new JsonPrimitive(number) : JsonNull.INSTANCE;
    }

    private static String reason(Throwable failure) {
        if (failure instanceof InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        return failure.getClass().getSimpleName();
    }
}
