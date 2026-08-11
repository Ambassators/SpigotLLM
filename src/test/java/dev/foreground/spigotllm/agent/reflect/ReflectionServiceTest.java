package dev.foreground.spigotllm.agent.reflect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReflectionServiceTest {
    private ReflectionService service;
    private Sample sample;

    @BeforeEach
    void setUp() {
        sample = new Sample("initial");
        service = new ReflectionService(new RootResolver() {
            @Override public Object resolveRoot(String owner, String root, JsonObject arguments) {
                if ("sample".equals(root)) return sample;
                if ("array".equals(root)) return new int[] { 2, 4, 6 };
                if ("list".equals(root)) return new ArrayList<String>(Arrays.asList("a", "b"));
                if ("map".equals(root)) {
                    Map<String, Integer> map = new LinkedHashMap<String, Integer>();
                    map.put("answer", 42);
                    return map;
                }
                return null;
            }

            @Override public ClassLoader resolveClassLoader(String owner, String loader, JsonObject arguments) {
                return ReflectionServiceTest.class.getClassLoader();
            }
        });
    }

    @Test
    void resolvesRootsAndProtectsHandlesAcrossOwners() throws Exception {
        JsonObject handle = root("owner-a", "sample");
        assertEquals(Sample.class.getName(), handle.get("type").getAsString());

        JsonObject get = object("target", handle, "field", "secret");
        assertEquals("initial", service.dispatch("owner-a", "reflect.get", get).getAsString());

        JsonObject denied = service.execute("owner-b", "reflect.get", get);
        assertFalse(denied.get("ok").getAsBoolean());
        assertEquals("unknown_handle", denied.getAsJsonObject("error").get("code").getAsString());
    }

    @Test
    void getsAndSetsPrivateInheritedAndStaticFields() throws Exception {
        JsonObject target = root("owner", "sample");
        JsonObject inherited = object("target", target, "field", "inherited");
        assertEquals(7, service.dispatch("owner", "reflect.get", inherited).getAsInt());

        JsonObject set = object("target", target, "field", "secret", "value", "changed");
        JsonObject result = service.dispatch("owner", "reflect.set", set).getAsJsonObject();
        assertTrue(result.get("changed").getAsBoolean());
        assertEquals("changed", sample.secret);

        JsonObject type = classHandle(Sample.class);
        JsonObject staticField = object("target", type, "field", "staticValue", "value", 31);
        service.dispatch("owner", "reflect.set", staticField);
        assertEquals(31, Sample.staticValue);
    }

    @Test
    void invokesPrivateInheritedOverloadedAndVarargsMethods() throws Exception {
        JsonObject target = root("owner", "sample");

        JsonObject privateCall = object("target", target, "method", "whisper");
        privateCall.add("arguments", array("hello"));
        assertEquals("hello:changed?", invoke(privateCall));

        JsonObject inheritedCall = object("target", target, "method", "multiply");
        inheritedCall.add("arguments", array(6, 7));
        assertEquals("42", invoke(inheritedCall));

        JsonObject overload = object("target", target, "method", "overload");
        overload.add("arguments", array(5));
        overload.add("parameterTypes", array("long"));
        assertEquals("long", invoke(overload));

        JsonObject varargs = object("target", target, "method", "join");
        varargs.add("arguments", array("/", "a", "b", "c"));
        assertEquals("a/b/c", invoke(varargs));
    }

    @Test
    void constructsPrivateTypesWithExactParameterTypes() throws Exception {
        JsonObject type = classHandle(PrivateConstructed.class);
        JsonObject args = object("target", type);
        args.add("arguments", array("made", 9));
        args.add("parameterTypes", array("java.lang.String", "int"));

        JsonObject instance = service.dispatch("owner", "reflect.construct", args).getAsJsonObject();
        JsonObject field = object("target", instance, "field", "value");
        assertEquals("made:9", service.dispatch("owner", "reflect.get", field).getAsString());
    }

    @Test
    void convertsEnumsUuidsArraysAndHandles() throws Exception {
        JsonObject target = root("owner", "sample");
        UUID uuid = UUID.randomUUID();

        JsonObject enumCall = object("target", target, "method", "enumName");
        enumCall.add("arguments", array("SECOND"));
        assertEquals("SECOND", invoke(enumCall));

        JsonObject uuidCall = object("target", target, "method", "uuidText");
        uuidCall.add("arguments", array(uuid.toString()));
        assertEquals(uuid.toString(), invoke(uuidCall));

        JsonObject sumCall = object("target", target, "method", "sum");
        sumCall.add("arguments", array(array(1, 2, 3)));
        assertEquals("6", invoke(sumCall));

        JsonObject other = root("owner", "sample");
        JsonObject handleCall = object("target", target, "method", "same");
        handleCall.add("arguments", array(other));
        assertEquals("true", invoke(handleCall));
    }

    @Test
    void getsAndSetsArraysListsAndMaps() throws Exception {
        JsonObject array = root("owner", "array");
        assertEquals(4, service.dispatch("owner", "reflect.indexGet", object("target", array, "index", 1)).getAsInt());
        JsonObject setArray = object("target", array, "index", 1, "value", 8);
        service.dispatch("owner", "reflect.indexSet", setArray);
        assertEquals(8, service.dispatch("owner", "reflect.indexGet", object("target", array, "index", 1)).getAsInt());

        JsonObject list = root("owner", "list");
        service.dispatch("owner", "reflect.indexSet", object("target", list, "index", 0, "value", "z"));
        assertEquals("z", service.dispatch("owner", "reflect.indexGet", object("target", list, "index", 0)).getAsString());

        JsonObject map = root("owner", "map");
        assertEquals(42, service.dispatch("owner", "reflect.indexGet", object("target", map, "key", "answer")).getAsInt());
        service.dispatch("owner", "reflect.indexSet", object("target", map, "key", "answer", "value", 43));
        assertEquals(43, service.dispatch("owner", "reflect.indexGet", object("target", map, "key", "answer")).getAsInt());
    }

    @Test
    void describesMembersWithFilteringAndPagination() throws Exception {
        JsonObject target = root("owner", "sample");
        JsonObject args = object("target", target, "kind", "method", "filter", "overload", "limit", 1);
        JsonObject description = service.dispatch("owner", "reflect.describe", args).getAsJsonObject();

        assertEquals(Sample.class.getName(), description.get("type").getAsString());
        assertEquals(2, description.get("total").getAsInt());
        assertEquals(1, description.getAsJsonArray("members").size());
        assertTrue(description.get("hasMore").getAsBoolean());
        assertEquals("method", description.getAsJsonArray("members").get(0).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void loadsPrimitiveAndArrayClassesAndReleasesHandles() throws Exception {
        JsonObject stringArray = classHandle(String[].class);
        assertEquals("java.lang.String[]", stringArray.get("type").getAsString());

        JsonObject release = object("target", stringArray);
        assertTrue(service.dispatch("owner", "reflect.release", release).getAsJsonObject().get("released").getAsBoolean());
        assertFalse(service.dispatch("owner", "reflect.release", release).getAsJsonObject().get("released").getAsBoolean());

        JsonObject primitiveArgs = object("name", "int", "loader", "context");
        JsonObject primitive = service.dispatch("owner", "reflect.class", primitiveArgs).getAsJsonObject();
        assertNotEquals(stringArray.get("$handle").getAsString(), primitive.get("$handle").getAsString());
        assertEquals("int", primitive.get("type").getAsString());
    }

    @Test
    void preciselyReportsWhenReflectionAndMethodHandlesAreBothBlocked() throws Exception {
        String handleId = service.getHandles().register("owner", new StringBuilder("text"));
        JsonObject handle = new JsonObject();
        handle.addProperty("$handle", handleId);
        JsonObject response = service.execute("owner", "reflect.get",
                object("target", handle, "field", "value"));

        // Java 8 permits this access; module-aware JVMs normally block both
        // routes. Either result is valid, but a denial must stay specific.
        if (!response.get("ok").getAsBoolean()) {
            JsonObject error = response.getAsJsonObject("error");
            assertEquals("access_denied", error.get("code").getAsString());
            assertTrue(error.get("message").getAsString().contains("Reflection and MethodHandles"));
        }
    }

    private String invoke(JsonObject args) throws Exception {
        JsonElement result = service.dispatch("owner", "reflect.invoke", args);
        return result.isJsonPrimitive() ? result.getAsString() : result.toString();
    }

    private JsonObject root(String owner, String name) throws Exception {
        return service.dispatch(owner, "reflect.root", object("root", name)).getAsJsonObject();
    }

    private JsonObject classHandle(Class<?> type) throws Exception {
        JsonObject args = object("name", type.getName(), "loader", "context");
        return service.dispatch("owner", "reflect.class", args).getAsJsonObject();
    }

    private static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int index = 0; index < pairs.length; index += 2) {
            String name = (String) pairs[index];
            Object value = pairs[index + 1];
            if (value instanceof JsonElement) result.add(name, (JsonElement) value);
            else if (value instanceof String) result.addProperty(name, (String) value);
            else if (value instanceof Number) result.addProperty(name, (Number) value);
            else if (value instanceof Boolean) result.addProperty(name, (Boolean) value);
            else throw new IllegalArgumentException("Unsupported test value");
        }
        return result;
    }

    private static JsonArray array(Object... values) {
        JsonArray result = new JsonArray();
        for (Object value : values) {
            if (value instanceof JsonElement) result.add((JsonElement) value);
            else if (value instanceof String) result.add((String) value);
            else if (value instanceof Number) result.add((Number) value);
            else if (value instanceof Boolean) result.add((Boolean) value);
            else throw new IllegalArgumentException("Unsupported test value");
        }
        return result;
    }

    private static class ParentSample {
        private int inherited = 7;

        private int multiply(int left, int right) {
            return left * right;
        }
    }

    private static final class Sample extends ParentSample {
        private static int staticValue = 1;
        private String secret;

        private Sample(String secret) {
            this.secret = secret;
        }

        private String whisper(String prefix) {
            return prefix + ":changed?";
        }

        private String overload(int ignored) {
            return "int";
        }

        private String overload(long ignored) {
            return "long";
        }

        private String join(String separator, String... values) {
            return String.join(separator, values);
        }

        private int sum(int[] values) {
            int total = 0;
            for (int value : values) total += value;
            return total;
        }

        private String enumName(Choice choice) {
            return choice.name();
        }

        private String uuidText(UUID uuid) {
            return uuid.toString();
        }

        private boolean same(Sample other) {
            return this == other;
        }
    }

    private enum Choice { FIRST, SECOND }

    private static final class PrivateConstructed {
        private final String value;

        private PrivateConstructed(String value, int number) {
            this.value = value + ":" + number;
        }
    }
}
