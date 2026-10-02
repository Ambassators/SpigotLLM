package dev.foreground.spigotllm.agent.spark;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.Map;

/** Strict and bounded JSON handling for untrusted profiler exports. */
final class SparkJson {
    private SparkJson() { }

    static JsonObject parse(Reader input) throws IOException {
        JsonReader reader = new JsonReader(input);
        reader.setStrictness(Strictness.STRICT);
        JsonElement result = read(reader, 0, new int[]{0});
        if (!result.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
            throw invalid("Expected exactly one JSON object.");
        }
        return result.getAsJsonObject();
    }

    private static JsonElement read(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 64 || ++count[0] > 2_000_000) throw invalid("JSON nesting or element limit exceeded.");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw invalid("Duplicate JSON field: " + key.substring(0, Math.min(80, key.length())));
                    object.add(key, read(reader, depth + 1, count));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, count));
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER:
                String number = reader.nextString();
                if (number.length() > 128) throw invalid("Number is too long.");
                BigDecimal decimal = new BigDecimal(number);
                if (!Double.isFinite(decimal.doubleValue())) throw invalid("Non-finite number.");
                return new JsonPrimitive(decimal);
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw invalid("Unexpected JSON token.");
        }
    }

    static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || value.isJsonNull()) return new JsonObject();
        if (!value.isJsonObject()) throw invalid(key + " must be an object.");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || value.isJsonNull()) return new JsonArray();
        if (!value.isJsonArray()) throw invalid(key + " must be an array.");
        return value.getAsJsonArray();
    }

    static String text(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid(key + " must be a string.");
        return value.getAsString();
    }

    static String label(String value) {
        return value.length() > 512 ? value.substring(0, 512) + "..." : value;
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw invalid(key + " must be boolean.");
        return value.getAsBoolean();
    }

    static double number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) throw invalid("Expected a finite number.");
        try {
            double result = value.getAsDouble();
            if (!Double.isFinite(result)) throw invalid("Expected a finite number.");
            return result;
        } catch (NumberFormatException e) { throw invalid("Expected a finite number."); }
    }

    static double number(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? number(object.get(key)) : fallback;
    }

    static int integer(JsonObject object, String key, int fallback, int min, int max) {
        double value = number(object, key, fallback);
        if (value < min || value > max || value != Math.rint(value)) throw invalid(key + " must be an integer in " + min + ".." + max + ".");
        return (int) value;
    }

    static JsonObject select(JsonObject from, String... keys) {
        JsonObject result = new JsonObject();
        for (String key : keys) if (from.has(key)) result.add(key, bounded(from.get(key), 0));
        return result;
    }

    // Metadata is secondary evidence, never an unbounded copy of the input.
    static JsonElement bounded(JsonElement value, int depth) {
        if (depth > 8) return new JsonPrimitive("[depth limited]");
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonPrimitive()) {
            return value.getAsJsonPrimitive().isString() ? new JsonPrimitive(label(value.getAsString())) : value.deepCopy();
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                if (result.size() >= 32) break;
                result.add(bounded(item, depth + 1));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (result.size() >= 32) break;
            result.add(label(entry.getKey()), bounded(entry.getValue(), depth + 1));
        }
        return result;
    }

    static SparkException invalid(String message) { return new SparkException("spark_invalid_data", message); }
}
