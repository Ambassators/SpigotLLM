package dev.foreground.spigotllm.agent.reflect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Synchronous deep-reflection dispatcher for the agent runtime bridge.
 *
 * <p>The caller is responsible for invoking this service on Bukkit's primary
 * thread. The implementation deliberately has no Bukkit dependency, allowing
 * the bridge to own scheduling and making the reflection engine unit-testable.</p>
 *
 * <p>Input handles are JSON objects of the form
 * {@code {"$handle":"h_..."}}. Non-JSON return values are represented using
 * the same shape, with informational {@code type} and {@code display} fields.</p>
 */
public final class ReflectionService {
    public static final int DEFAULT_MAXIMUM_HANDLES = 512;
    private static final int DEFAULT_DESCRIBE_LIMIT = 100;
    private static final int MAX_DESCRIBE_LIMIT = 500;

    private final RootResolver roots;
    private final ObjectHandleRegistry handles;

    public ReflectionService(RootResolver roots) {
        this(roots, new ObjectHandleRegistry(DEFAULT_MAXIMUM_HANDLES));
    }

    public ReflectionService(RootResolver roots, int maximumHandlesPerOwner) {
        this(roots, new ObjectHandleRegistry(maximumHandlesPerOwner));
    }

    public ReflectionService(RootResolver roots, ObjectHandleRegistry handles) {
        if (roots == null) throw new IllegalArgumentException("roots is required");
        if (handles == null) throw new IllegalArgumentException("handles is required");
        this.roots = roots;
        this.handles = handles;
    }

    /**
     * Execute one operation and always return a structured response. Expected
     * reflection failures are encoded as {@code {ok:false,error:{code,message}}}.
     */
    public JsonObject execute(String owner, String operation, JsonObject arguments) {
        JsonObject response = new JsonObject();
        try {
            JsonElement result = dispatch(owner, operation, arguments == null ? new JsonObject() : arguments);
            response.addProperty("ok", true);
            response.add("result", result == null ? JsonNull.INSTANCE : result);
        } catch (ReflectionException e) {
            response.addProperty("ok", false);
            response.add("error", error(e.getCode(), message(e)));
        } catch (RuntimeException e) {
            response.addProperty("ok", false);
            response.add("error", error("reflection_failed", e.getClass().getName() + ": " + message(e)));
        }
        return response;
    }

    /** Execute one operation, throwing a typed failure for bridge-native error handling. */
    public JsonElement dispatch(String owner, String operation, JsonObject arguments) throws ReflectionException {
        requireText(owner, "owner");
        String normalized = requireText(operation, "operation");
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        if ("reflect.root".equals(normalized)) return root(owner, args);
        if ("reflect.class".equals(normalized)) return classForName(owner, args);
        if ("reflect.describe".equals(normalized)) return describe(owner, args);
        if ("reflect.get".equals(normalized)) return get(owner, args);
        if ("reflect.set".equals(normalized)) return set(owner, args);
        if ("reflect.invoke".equals(normalized)) return invoke(owner, args);
        if ("reflect.construct".equals(normalized)) return construct(owner, args);
        if ("reflect.indexGet".equals(normalized)) return indexGet(owner, args);
        if ("reflect.indexSet".equals(normalized)) return indexSet(owner, args);
        if ("reflect.release".equals(normalized)) return release(owner, args);
        throw new ReflectionException("unknown_operation", "Unsupported reflection operation: " + normalized);
    }

    /** Release every transient object retained for a completed agent owner. */
    public int releaseOwner(String owner) throws ReflectionException {
        return handles.releaseOwner(owner);
    }

    public ObjectHandleRegistry getHandles() {
        return handles;
    }

    private JsonElement root(String owner, JsonObject args) throws ReflectionException {
        String root = requiredString(args, "root");
        try {
            Object value = roots.resolveRoot(owner, root, args);
            if (value == null) throw new ReflectionException("root_not_found", "Root was not found: " + root);
            return encode(owner, value);
        } catch (ReflectionException e) {
            throw e;
        } catch (Exception e) {
            throw new ReflectionException("root_failed", "Could not resolve root '" + root + "': " + message(e), e);
        }
    }

    private JsonElement classForName(String owner, JsonObject args) throws ReflectionException {
        String name = requiredString(args, "name");
        String loaderName = optionalString(args, "loader", "context");
        try {
            ClassLoader loader = roots.resolveClassLoader(owner, loaderName, args);
            return encode(owner, loadClass(name, loader));
        } catch (ReflectionException e) {
            throw e;
        } catch (Exception e) {
            throw new ReflectionException("class_not_found", "Could not load class '" + name + "': " + message(e), e);
        }
    }

    private JsonElement describe(String owner, JsonObject args) throws ReflectionException {
        Object target = decodeHandle(owner, required(args, "target"));
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        String kind = optionalString(args, "kind", "all").toLowerCase(Locale.ROOT);
        if (!("all".equals(kind) || "field".equals(kind) || "method".equals(kind)
                || "constructor".equals(kind))) {
            throw new ReflectionException("invalid_argument", "kind must be all, field, method, or constructor.");
        }
        String filter = optionalString(args, "filter", "").toLowerCase(Locale.ROOT);
        boolean inherited = optionalBoolean(args, "inherited", true);
        boolean synthetic = optionalBoolean(args, "includeSynthetic", false);
        int offset = boundedInt(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = boundedInt(args, "limit", DEFAULT_DESCRIBE_LIMIT, 1, MAX_DESCRIBE_LIMIT);

        List<JsonObject> members = new ArrayList<JsonObject>();
        if ("all".equals(kind) || "field".equals(kind)) {
            for (Field field : fields(type, inherited)) {
                if ((!synthetic && field.isSynthetic()) || !matches(filter, field.getName(), field.toGenericString())) continue;
                JsonObject item = member("field", field);
                item.addProperty("type", typeName(field.getType()));
                members.add(item);
            }
        }
        if ("all".equals(kind) || "method".equals(kind)) {
            for (Method method : methods(type, inherited)) {
                if ((!synthetic && (method.isSynthetic() || method.isBridge()))
                        || !matches(filter, method.getName(), method.toGenericString())) continue;
                JsonObject item = member("method", method);
                item.addProperty("returnType", typeName(method.getReturnType()));
                item.add("parameterTypes", typeNames(method.getParameterTypes()));
                item.addProperty("varArgs", method.isVarArgs());
                members.add(item);
            }
        }
        if ("all".equals(kind) || "constructor".equals(kind)) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if ((!synthetic && constructor.isSynthetic())
                        || !matches(filter, constructor.getName(), constructor.toGenericString())) continue;
                JsonObject item = member("constructor", constructor);
                item.add("parameterTypes", typeNames(constructor.getParameterTypes()));
                item.addProperty("varArgs", constructor.isVarArgs());
                members.add(item);
            }
        }
        Collections.sort(members, new Comparator<JsonObject>() {
            @Override public int compare(JsonObject left, JsonObject right) {
                return memberSortKey(left).compareTo(memberSortKey(right));
            }
        });

        JsonArray page = new JsonArray();
        int end = Math.min(members.size(), offset > members.size() ? members.size() : offset + limit);
        for (int index = Math.min(offset, members.size()); index < end; index++) page.add(members.get(index));
        JsonObject result = new JsonObject();
        result.addProperty("type", typeName(type));
        result.addProperty("total", members.size());
        result.addProperty("offset", offset);
        result.addProperty("limit", limit);
        result.addProperty("hasMore", end < members.size());
        result.add("members", page);
        return result;
    }

    private JsonElement get(String owner, JsonObject args) throws ReflectionException {
        Object target = decodeHandle(owner, required(args, "target"));
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        String name = requiredString(args, "field");
        Field field = findField(type, name, optionalNullableString(args, "declaringClass"));
        boolean staticField = Modifier.isStatic(field.getModifiers());
        if (target instanceof Class<?> && !staticField) {
            throw new ReflectionException("instance_required", "Field '" + name + "' requires an object instance.");
        }
        return encode(owner, readField(field, staticField ? null : target));
    }

    private JsonElement set(String owner, JsonObject args) throws ReflectionException {
        Object target = decodeHandle(owner, required(args, "target"));
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        String name = requiredString(args, "field");
        Field field = findField(type, name, optionalNullableString(args, "declaringClass"));
        boolean staticField = Modifier.isStatic(field.getModifiers());
        if (target instanceof Class<?> && !staticField) {
            throw new ReflectionException("instance_required", "Field '" + name + "' requires an object instance.");
        }
        Conversion conversion = convert(owner, required(args, "value"), field.getType());
        Object receiver = staticField ? null : target;
        try {
            writeField(field, receiver, conversion.value);
            Object actual = readField(field, receiver);
            if (!equivalent(conversion.value, actual)) {
                throw new ReflectionException("field_write_failed",
                        "The runtime did not apply the write to field '" + name + "'.");
            }
            JsonObject result = new JsonObject();
            result.addProperty("changed", true);
            result.add("value", encode(owner, actual));
            return result;
        } catch (IllegalArgumentException e) {
            throw new ReflectionException("conversion_failed", "Cannot assign field '" + name + "': " + message(e), e);
        }
    }

    private JsonElement invoke(String owner, JsonObject args) throws ReflectionException {
        Object target = decodeHandle(owner, required(args, "target"));
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        String name = requiredString(args, "method");
        JsonArray values = optionalArray(args, "arguments");
        String declaring = optionalNullableString(args, "declaringClass");
        Method method;
        ConvertedArguments converted;
        JsonArray exact = optionalNullableArray(args, "parameterTypes");
        if (exact != null) {
            ClassLoader loader = type.getClassLoader();
            Class<?>[] parameterTypes = resolveTypes(exact, loader);
            method = findExactMethod(type, name, parameterTypes, declaring);
            converted = convertArguments(owner, values, method.getParameterTypes(), method.isVarArgs());
        } else {
            MethodSelection selection = selectMethod(owner, type, name, declaring, values,
                    target instanceof Class<?>);
            method = selection.method;
            converted = selection.arguments;
        }
        boolean staticMethod = Modifier.isStatic(method.getModifiers());
        if (target instanceof Class<?> && !staticMethod) {
            throw new ReflectionException("instance_required", "Method '" + name + "' requires an object instance.");
        }
        Object value = invokeMethod(method, staticMethod ? null : target, converted.values);
        return method.getReturnType() == Void.TYPE ? JsonNull.INSTANCE : encode(owner, value);
    }

    private JsonElement construct(String owner, JsonObject args) throws ReflectionException {
        Class<?> type = resolveClassTarget(owner, required(args, "target"));
        JsonArray values = optionalArray(args, "arguments");
        Constructor<?> constructor;
        ConvertedArguments converted;
        JsonArray exact = optionalNullableArray(args, "parameterTypes");
        if (exact != null) {
            Class<?>[] parameterTypes = resolveTypes(exact, type.getClassLoader());
            constructor = findExactConstructor(type, parameterTypes);
            converted = convertArguments(owner, values, constructor.getParameterTypes(), constructor.isVarArgs());
        } else {
            ConstructorSelection selection = selectConstructor(owner, type, values);
            constructor = selection.constructor;
            converted = selection.arguments;
        }
        return encode(owner, invokeConstructor(constructor, converted.values));
    }

    private JsonElement indexGet(String owner, JsonObject args) throws ReflectionException {
        Object target = decodeHandle(owner, required(args, "target"));
        try {
            if (target.getClass().isArray()) {
                return encode(owner, Array.get(target, requiredIndex(args)));
            }
            if (target instanceof List<?>) {
                return encode(owner, ((List<?>) target).get(requiredIndex(args)));
            }
            if (target instanceof Map<?, ?>) {
                Object key = decodeUntyped(owner, required(args, "key"));
                return encode(owner, ((Map<?, ?>) target).get(key));
            }
        } catch (IndexOutOfBoundsException e) {
            throw new ReflectionException("index_out_of_bounds", message(e), e);
        }
        throw new ReflectionException("not_indexable", "Target is not an array, List, or Map.");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JsonElement indexSet(String owner, JsonObject args) throws ReflectionException {
        Object target = decodeHandle(owner, required(args, "target"));
        JsonElement input = required(args, "value");
        try {
            Object previous;
            Object applied;
            if (target.getClass().isArray()) {
                int index = requiredIndex(args);
                previous = Array.get(target, index);
                applied = convert(owner, input, target.getClass().getComponentType()).value;
                Array.set(target, index, applied);
            } else if (target instanceof List<?>) {
                int index = requiredIndex(args);
                applied = decodeUntyped(owner, input);
                previous = ((List) target).set(index, applied);
            } else if (target instanceof Map<?, ?>) {
                Object key = decodeUntyped(owner, required(args, "key"));
                applied = decodeUntyped(owner, input);
                previous = ((Map) target).put(key, applied);
            } else {
                throw new ReflectionException("not_indexable", "Target is not an array, List, or Map.");
            }
            JsonObject result = new JsonObject();
            result.add("previous", encode(owner, previous));
            result.add("value", encode(owner, applied));
            return result;
        } catch (IndexOutOfBoundsException e) {
            throw new ReflectionException("index_out_of_bounds", message(e), e);
        } catch (ArrayStoreException e) {
            throw new ReflectionException("conversion_failed", message(e), e);
        }
    }

    private JsonElement release(String owner, JsonObject args) throws ReflectionException {
        String handle = handleId(required(args, "target"));
        JsonObject result = new JsonObject();
        result.addProperty("released", handles.release(owner, handle));
        return result;
    }

    private MethodSelection selectMethod(String owner, Class<?> type, String name, String declaring,
                                         JsonArray inputs, boolean staticOnly) throws ReflectionException {
        List<MethodSelection> matches = new ArrayList<MethodSelection>();
        for (Method method : methods(type, true)) {
            if (!method.getName().equals(name)) continue;
            if (declaring != null && !method.getDeclaringClass().getName().equals(declaring)) continue;
            if (staticOnly && !Modifier.isStatic(method.getModifiers())) continue;
            try {
                ConvertedArguments converted = convertArguments(owner, inputs, method.getParameterTypes(), method.isVarArgs());
                matches.add(new MethodSelection(method, converted));
            } catch (ReflectionException ignored) { }
        }
        if (matches.isEmpty()) throw new ReflectionException("method_not_found", "No compatible method named '" + name + "'.");
        Collections.sort(matches, new Comparator<MethodSelection>() {
            @Override public int compare(MethodSelection left, MethodSelection right) {
                int score = Integer.compare(left.arguments.score, right.arguments.score);
                return score != 0 ? score : signature(left.method).compareTo(signature(right.method));
            }
        });
        if (matches.size() > 1 && matches.get(0).arguments.score == matches.get(1).arguments.score
                && !Arrays.equals(matches.get(0).method.getParameterTypes(), matches.get(1).method.getParameterTypes())) {
            throw new ReflectionException("ambiguous_method",
                    "Multiple overloads match method '" + name + "'; provide parameterTypes.");
        }
        return matches.get(0);
    }

    private ConstructorSelection selectConstructor(String owner, Class<?> type, JsonArray inputs)
            throws ReflectionException {
        List<ConstructorSelection> matches = new ArrayList<ConstructorSelection>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try {
                ConvertedArguments converted = convertArguments(owner, inputs, constructor.getParameterTypes(), constructor.isVarArgs());
                matches.add(new ConstructorSelection(constructor, converted));
            } catch (ReflectionException ignored) { }
        }
        if (matches.isEmpty()) throw new ReflectionException("constructor_not_found", "No compatible constructor for " + typeName(type) + ".");
        Collections.sort(matches, new Comparator<ConstructorSelection>() {
            @Override public int compare(ConstructorSelection left, ConstructorSelection right) {
                int score = Integer.compare(left.arguments.score, right.arguments.score);
                return score != 0 ? score : signature(left.constructor).compareTo(signature(right.constructor));
            }
        });
        if (matches.size() > 1 && matches.get(0).arguments.score == matches.get(1).arguments.score
                && !Arrays.equals(matches.get(0).constructor.getParameterTypes(), matches.get(1).constructor.getParameterTypes())) {
            throw new ReflectionException("ambiguous_constructor", "Multiple constructors match; provide parameterTypes.");
        }
        return matches.get(0);
    }

    private ConvertedArguments convertArguments(String owner, JsonArray inputs, Class<?>[] types, boolean varArgs)
            throws ReflectionException {
        int inputCount = inputs.size();
        if (!varArgs && inputCount != types.length) throw new ReflectionException("arity_mismatch", "Argument count does not match.");
        if (varArgs && inputCount < types.length - 1) throw new ReflectionException("arity_mismatch", "Argument count does not match.");
        Object[] converted = new Object[types.length];
        int score = varArgs ? 4 : 0;
        int fixed = varArgs ? types.length - 1 : types.length;
        for (int index = 0; index < fixed; index++) {
            Conversion value = convert(owner, inputs.get(index), types[index]);
            converted[index] = value.value;
            score += value.score;
        }
        if (varArgs) {
            Class<?> arrayType = types[types.length - 1];
            Class<?> component = arrayType.getComponentType();
            if (inputCount == types.length && inputs.get(inputCount - 1).isJsonArray()) {
                Conversion packed = convert(owner, inputs.get(inputCount - 1), arrayType);
                converted[types.length - 1] = packed.value;
                score += packed.score;
            } else {
                int count = inputCount - fixed;
                Object array = Array.newInstance(component, count);
                for (int index = 0; index < count; index++) {
                    Conversion value = convert(owner, inputs.get(fixed + index), component);
                    Array.set(array, index, value.value);
                    score += value.score;
                }
                converted[types.length - 1] = array;
            }
        }
        return new ConvertedArguments(converted, score);
    }

    private Conversion convert(String owner, JsonElement input, Class<?> target) throws ReflectionException {
        if (input == null || input.isJsonNull()) {
            if (target.isPrimitive()) throw new ReflectionException("conversion_failed", "null cannot be converted to " + typeName(target));
            return new Conversion(null, 8);
        }
        if (isHandle(input)) {
            Object value = handles.resolve(owner, handleId(input));
            Class<?> boxed = box(target);
            if (!boxed.isInstance(value)) {
                throw new ReflectionException("conversion_failed", typeName(value.getClass()) + " is not assignable to " + typeName(target));
            }
            return new Conversion(value, value.getClass() == boxed ? 0 : inheritanceDistance(value.getClass(), boxed));
        }
        if (target.isArray()) {
            if (!input.isJsonArray()) throw new ReflectionException("conversion_failed", "Expected a JSON array for " + typeName(target));
            JsonArray values = input.getAsJsonArray();
            Object array = Array.newInstance(target.getComponentType(), values.size());
            int score = 2;
            for (int index = 0; index < values.size(); index++) {
                Conversion value = convert(owner, values.get(index), target.getComponentType());
                Array.set(array, index, value.value);
                score += value.score;
            }
            return new Conversion(array, score);
        }
        if (target == Object.class) return new Conversion(decodeUntyped(owner, input), 20);
        if (!input.isJsonPrimitive()) throw new ReflectionException("conversion_failed", "Expected a primitive or handle for " + typeName(target));
        JsonPrimitive primitive = input.getAsJsonPrimitive();
        Class<?> boxed = box(target);
        if (boxed == String.class) {
            if (!primitive.isString()) throw new ReflectionException("conversion_failed", "Expected a string.");
            return new Conversion(primitive.getAsString(), 0);
        }
        if (boxed == Boolean.class) {
            if (!primitive.isBoolean()) throw new ReflectionException("conversion_failed", "Expected a boolean.");
            return new Conversion(primitive.getAsBoolean(), 0);
        }
        if (boxed == Character.class) {
            if (!primitive.isString() || primitive.getAsString().length() != 1) {
                throw new ReflectionException("conversion_failed", "Expected a one-character string.");
            }
            return new Conversion(primitive.getAsString().charAt(0), 1);
        }
        if (boxed == UUID.class) {
            if (!primitive.isString()) throw new ReflectionException("conversion_failed", "Expected a UUID string.");
            try {
                return new Conversion(UUID.fromString(primitive.getAsString()), 1);
            } catch (IllegalArgumentException e) {
                throw new ReflectionException("conversion_failed", "Invalid UUID string.", e);
            }
        }
        if (boxed == Class.class) {
            if (!primitive.isString()) throw new ReflectionException("conversion_failed", "Expected a class name string.");
            try {
                return new Conversion(loadClass(primitive.getAsString(), Thread.currentThread().getContextClassLoader()), 3);
            } catch (ClassNotFoundException e) {
                throw new ReflectionException("class_not_found", "Could not load class '" + primitive.getAsString() + "'.", e);
            }
        }
        if (boxed.isEnum()) {
            if (!primitive.isString()) throw new ReflectionException("conversion_failed", "Expected an enum name string.");
            Object[] constants = boxed.getEnumConstants();
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(primitive.getAsString())) return new Conversion(constant, 1);
            }
            throw new ReflectionException("conversion_failed", "Unknown " + typeName(boxed) + " value: " + primitive.getAsString());
        }
        if (Number.class.isAssignableFrom(boxed)) {
            if (!primitive.isNumber()) throw new ReflectionException("conversion_failed", "Expected a number.");
            return new Conversion(convertNumber(primitive.getAsBigDecimal(), boxed), numericScore(boxed));
        }
        throw new ReflectionException("conversion_failed", "JSON value cannot be converted to " + typeName(target));
    }

    private Object decodeUntyped(String owner, JsonElement input) throws ReflectionException {
        if (input == null || input.isJsonNull()) return null;
        if (isHandle(input)) return handles.resolve(owner, handleId(input));
        if (input.isJsonArray()) {
            List<Object> values = new ArrayList<Object>();
            for (JsonElement element : input.getAsJsonArray()) values.add(decodeUntyped(owner, element));
            return values;
        }
        if (input.isJsonObject()) {
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, JsonElement> entry : input.getAsJsonObject().entrySet()) {
                values.put(entry.getKey(), decodeUntyped(owner, entry.getValue()));
            }
            return values;
        }
        JsonPrimitive primitive = input.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isString()) return primitive.getAsString();
        return primitive.getAsBigDecimal();
    }

    /**
     * Encode a runtime result using the bridge's primitive-or-handle rules.
     * This is useful to mini-context emitters that return values outside a
     * direct reflection operation.
     */
    public JsonElement encode(String owner, Object value) throws ReflectionException {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String) return new JsonPrimitive((String) value);
        if (value instanceof Character) return new JsonPrimitive((Character) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Enum<?>) return new JsonPrimitive(((Enum<?>) value).name());
        if (value instanceof UUID) return new JsonPrimitive(value.toString());
        JsonObject result = new JsonObject();
        result.addProperty("$handle", handles.register(owner, value));
        result.addProperty("type", typeName(value instanceof Class<?> ? (Class<?>) value : value.getClass()));
        result.addProperty("display", safeDisplay(value));
        return result;
    }

    /** Resolve an opaque handle while enforcing owner isolation. */
    public Object resolveHandle(String owner, String handle) throws ReflectionException {
        return handles.resolve(owner, handle);
    }

    private Class<?> resolveClassTarget(String owner, JsonElement element) throws ReflectionException {
        Object target = decodeHandle(owner, element);
        if (!(target instanceof Class<?>)) throw new ReflectionException("class_required", "target must be a Class handle.");
        return (Class<?>) target;
    }

    private Object decodeHandle(String owner, JsonElement element) throws ReflectionException {
        if (!isHandle(element)) throw new ReflectionException("handle_required", "Expected an object handle.");
        return handles.resolve(owner, handleId(element));
    }

    private static Field findField(Class<?> type, String name, String declaring) throws ReflectionException {
        if (declaring != null) {
            Class<?> current = findDeclaringType(type, declaring);
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                throw new ReflectionException("field_not_found", "Field '" + name + "' was not declared by " + declaring + ".", e);
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) { }
        }
        Field interfaceField = findInterfaceField(type, name, new HashSet<Class<?>>());
        if (interfaceField != null) return interfaceField;
        throw new ReflectionException("field_not_found", "Field '" + name + "' was not found on " + typeName(type) + ".");
    }

    private static Field findInterfaceField(Class<?> type, String name, Set<Class<?>> visited) {
        for (Class<?> iface : type.getInterfaces()) {
            if (!visited.add(iface)) continue;
            try {
                return iface.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) { }
            Field nested = findInterfaceField(iface, name, visited);
            if (nested != null) return nested;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : findInterfaceField(parent, name, visited);
    }

    private static Method findExactMethod(Class<?> type, String name, Class<?>[] parameters, String declaring)
            throws ReflectionException {
        if (declaring != null) {
            Class<?> current = findDeclaringType(type, declaring);
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException e) {
                throw new ReflectionException("method_not_found", "Exact method was not declared by " + declaring + ".", e);
            }
        }
        for (Method method : methods(type, true)) {
            if (method.getName().equals(name) && Arrays.equals(method.getParameterTypes(), parameters)) return method;
        }
        throw new ReflectionException("method_not_found", "Exact method '" + name + "' was not found.");
    }

    private static Constructor<?> findExactConstructor(Class<?> type, Class<?>[] parameters) throws ReflectionException {
        try {
            return type.getDeclaredConstructor(parameters);
        } catch (NoSuchMethodException e) {
            throw new ReflectionException("constructor_not_found", "Exact constructor was not found on " + typeName(type) + ".", e);
        }
    }

    private static Class<?> findDeclaringType(Class<?> type, String name) throws ReflectionException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (current.getName().equals(name)) return current;
        }
        Class<?> iface = findInterface(type, name, new HashSet<Class<?>>());
        if (iface != null) return iface;
        throw new ReflectionException("declaring_class_not_found", name + " is not in the target type hierarchy.");
    }

    private static Class<?> findInterface(Class<?> type, String name, Set<Class<?>> visited) {
        for (Class<?> iface : type.getInterfaces()) {
            if (!visited.add(iface)) continue;
            if (iface.getName().equals(name)) return iface;
            Class<?> nested = findInterface(iface, name, visited);
            if (nested != null) return nested;
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? null : findInterface(parent, name, visited);
    }

    private static List<Field> fields(Class<?> type, boolean inherited) {
        List<Field> result = new ArrayList<Field>();
        for (Class<?> current = type; current != null; current = inherited ? current.getSuperclass() : null) {
            result.addAll(Arrays.asList(current.getDeclaredFields()));
            if (!inherited) break;
        }
        return result;
    }

    private static List<Method> methods(Class<?> type, boolean inherited) {
        Map<String, Method> result = new LinkedHashMap<String, Method>();
        collectMethods(type, inherited, result, new HashSet<Class<?>>());
        return new ArrayList<Method>(result.values());
    }

    private static void collectMethods(Class<?> type, boolean inherited, Map<String, Method> result, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return;
        for (Method method : type.getDeclaredMethods()) {
            String key = method.getName() + Arrays.toString(method.getParameterTypes());
            if (!result.containsKey(key)) result.put(key, method);
        }
        if (!inherited) return;
        for (Class<?> iface : type.getInterfaces()) collectMethods(iface, true, result, visited);
        collectMethods(type.getSuperclass(), true, result, visited);
    }

    private static Object readField(Field field, Object receiver) throws ReflectionException {
        Throwable reflectionFailure;
        try {
            ensureAccessible(field);
            return field.get(receiver);
        } catch (ReflectionException e) {
            reflectionFailure = e;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        }

        MethodHandle getter;
        try {
            getter = MethodHandles.lookup().unreflectGetter(field);
        } catch (IllegalAccessException e) {
            throw accessDenied(field, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw accessDenied(field, reflectionFailure, e);
        }
        try {
            return Modifier.isStatic(field.getModifiers())
                    ? getter.invokeWithArguments(Collections.emptyList())
                    : getter.invokeWithArguments(Collections.singletonList(receiver));
        } catch (Throwable e) {
            throw new ReflectionException("reflection_failed", "MethodHandles getter failed for "
                    + memberName(field) + ": " + message(e), e);
        }
    }

    private static void writeField(Field field, Object receiver, Object value) throws ReflectionException {
        Throwable reflectionFailure;
        try {
            ensureAccessible(field);
            field.set(receiver, value);
            return;
        } catch (ReflectionException e) {
            reflectionFailure = e;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        }

        MethodHandle setter;
        try {
            setter = MethodHandles.lookup().unreflectSetter(field);
        } catch (IllegalAccessException e) {
            throw accessDenied(field, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw accessDenied(field, reflectionFailure, e);
        }
        try {
            if (Modifier.isStatic(field.getModifiers())) {
                setter.invokeWithArguments(Collections.singletonList(value));
            } else {
                setter.invokeWithArguments(Arrays.asList(receiver, value));
            }
        } catch (Throwable e) {
            throw new ReflectionException("field_write_failed", "MethodHandles setter failed for "
                    + memberName(field) + ": " + message(e), e);
        }
    }

    private static Object invokeMethod(Method method, Object receiver, Object[] arguments)
            throws ReflectionException {
        Throwable reflectionFailure;
        try {
            ensureAccessible(method);
            return method.invoke(receiver, arguments);
        } catch (ReflectionException e) {
            reflectionFailure = e;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        } catch (InvocationTargetException e) {
            throw invocationFailed(e.getCause() == null ? e : e.getCause());
        } catch (IllegalArgumentException e) {
            throw new ReflectionException("invocation_failed", "Could not invoke method: " + message(e), e);
        }

        MethodHandle handle;
        try {
            handle = MethodHandles.lookup().unreflect(method);
        } catch (IllegalAccessException e) {
            throw accessDenied(method, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw accessDenied(method, reflectionFailure, e);
        }
        List<Object> invocation = new ArrayList<Object>(arguments.length + 1);
        if (!Modifier.isStatic(method.getModifiers())) invocation.add(receiver);
        Collections.addAll(invocation, arguments);
        try {
            return handle.invokeWithArguments(invocation);
        } catch (Throwable e) {
            throw invocationFailed(e);
        }
    }

    private static Object invokeConstructor(Constructor<?> constructor, Object[] arguments)
            throws ReflectionException {
        Throwable reflectionFailure;
        try {
            ensureAccessible(constructor);
            return constructor.newInstance(arguments);
        } catch (ReflectionException e) {
            reflectionFailure = e;
        } catch (IllegalAccessException e) {
            reflectionFailure = e;
        } catch (InvocationTargetException e) {
            throw constructionFailed(e.getCause() == null ? e : e.getCause());
        } catch (InstantiationException e) {
            throw constructionFailed(e);
        } catch (IllegalArgumentException e) {
            throw constructionFailed(e);
        }

        MethodHandle handle;
        try {
            handle = MethodHandles.lookup().unreflectConstructor(constructor);
        } catch (IllegalAccessException e) {
            throw accessDenied(constructor, reflectionFailure, e);
        } catch (RuntimeException e) {
            throw accessDenied(constructor, reflectionFailure, e);
        }
        try {
            return handle.invokeWithArguments(Arrays.asList(arguments));
        } catch (Throwable e) {
            throw constructionFailed(e);
        }
    }

    private static ReflectionException invocationFailed(Throwable cause) {
        return new ReflectionException("invocation_failed",
                cause.getClass().getName() + ": " + message(cause), cause);
    }

    private static ReflectionException constructionFailed(Throwable cause) {
        return new ReflectionException("construction_failed",
                cause.getClass().getName() + ": " + message(cause), cause);
    }

    private static void ensureAccessible(java.lang.reflect.AccessibleObject object) throws ReflectionException {
        try {
            if (!object.isAccessible()) object.setAccessible(true);
        } catch (SecurityException e) {
            throw new ReflectionException("access_denied", "The runtime denied reflective access to " + object + ".", e);
        } catch (RuntimeException e) {
            throw new ReflectionException("access_denied", "The JVM module/access policy blocked " + object
                    + ": " + message(e), e);
        }
    }

    private static ReflectionException accessDenied(Member member, Throwable reflectionFailure,
                                                    Throwable methodHandleFailure) {
        return new ReflectionException("access_denied", "Reflection and MethodHandles access were denied for "
                + memberName(member) + " (reflection: " + message(reflectionFailure)
                + "; MethodHandles: " + message(methodHandleFailure) + ").", methodHandleFailure);
    }

    private static String memberName(Member member) {
        return member.getDeclaringClass().getName() + "." + member.getName();
    }

    private static Class<?>[] resolveTypes(JsonArray names, ClassLoader loader) throws ReflectionException {
        Class<?>[] types = new Class<?>[names.size()];
        for (int index = 0; index < names.size(); index++) {
            JsonElement element = names.get(index);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new ReflectionException("invalid_argument", "parameterTypes must contain class-name strings.");
            }
            try {
                types[index] = loadClass(element.getAsString(), loader);
            } catch (ClassNotFoundException e) {
                throw new ReflectionException("class_not_found", "Could not load parameter type '" + element.getAsString() + "'.", e);
            }
        }
        return types;
    }

    private static Class<?> loadClass(String name, ClassLoader loader) throws ClassNotFoundException {
        if ("boolean".equals(name)) return Boolean.TYPE;
        if ("byte".equals(name)) return Byte.TYPE;
        if ("short".equals(name)) return Short.TYPE;
        if ("int".equals(name)) return Integer.TYPE;
        if ("long".equals(name)) return Long.TYPE;
        if ("float".equals(name)) return Float.TYPE;
        if ("double".equals(name)) return Double.TYPE;
        if ("char".equals(name)) return Character.TYPE;
        if ("void".equals(name)) return Void.TYPE;
        if (name.endsWith("[]")) {
            Class<?> component = loadClass(name.substring(0, name.length() - 2), loader);
            return Array.newInstance(component, 0).getClass();
        }
        return Class.forName(name, false, loader);
    }

    private static Object convertNumber(BigDecimal number, Class<?> target) throws ReflectionException {
        try {
            if (target == Byte.class) return number.byteValueExact();
            if (target == Short.class) return number.shortValueExact();
            if (target == Integer.class) return number.intValueExact();
            if (target == Long.class) return number.longValueExact();
            if (target == Float.class) {
                float value = number.floatValue();
                if (Float.isInfinite(value)) throw new ArithmeticException("float overflow");
                return value;
            }
            if (target == Double.class) {
                double value = number.doubleValue();
                if (Double.isInfinite(value)) throw new ArithmeticException("double overflow");
                return value;
            }
            if (target == BigInteger.class) return number.toBigIntegerExact();
            if (target == BigDecimal.class) return number;
            if (target == Number.class) return number;
        } catch (ArithmeticException e) {
            throw new ReflectionException("conversion_failed", "Number is out of range or not integral for " + typeName(target) + ".", e);
        }
        throw new ReflectionException("conversion_failed", "Unsupported numeric type " + typeName(target) + ".");
    }

    private static int numericScore(Class<?> boxed) {
        if (boxed == Integer.class) return 0;
        if (boxed == Long.class) return 1;
        if (boxed == Double.class) return 2;
        if (boxed == Float.class) return 3;
        if (boxed == Short.class) return 4;
        if (boxed == Byte.class) return 5;
        return 6;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Character.TYPE) return Character.class;
        return type;
    }

    private static int inheritanceDistance(Class<?> actual, Class<?> target) {
        if (actual == target) return 0;
        if (target.isInterface()) return 2;
        int distance = 1;
        for (Class<?> current = actual.getSuperclass(); current != null; current = current.getSuperclass()) {
            if (current == target) return distance;
            distance++;
        }
        return 10;
    }

    private static boolean equivalent(Object expected, Object actual) {
        if (expected == actual) return true;
        if (expected == null || actual == null) return false;
        if (expected.getClass().isArray() && actual.getClass().isArray()) {
            int length = Array.getLength(expected);
            if (length != Array.getLength(actual)) return false;
            for (int index = 0; index < length; index++) {
                if (!equivalent(Array.get(expected, index), Array.get(actual, index))) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static JsonObject member(String kind, Member member) {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind);
        item.addProperty("name", member.getName());
        item.addProperty("declaringClass", member.getDeclaringClass().getName());
        item.addProperty("modifiers", Modifier.toString(member.getModifiers()));
        return item;
    }

    private static String memberSortKey(JsonObject member) {
        String parameters = member.has("parameterTypes") ? member.get("parameterTypes").toString() : "";
        return member.get("kind").getAsString() + "|" + member.get("name").getAsString() + "|" + parameters
                + "|" + member.get("declaringClass").getAsString();
    }

    private static JsonArray typeNames(Class<?>[] types) {
        JsonArray result = new JsonArray();
        for (Class<?> type : types) result.add(typeName(type));
        return result;
    }

    private static String typeName(Class<?> type) {
        if (!type.isArray()) return type.getName();
        return typeName(type.getComponentType()) + "[]";
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName() + Arrays.toString(method.getParameterTypes());
    }

    private static String signature(Constructor<?> constructor) {
        return constructor.getDeclaringClass().getName() + Arrays.toString(constructor.getParameterTypes());
    }

    private static boolean matches(String filter, String name, String signature) {
        return filter.isEmpty() || name.toLowerCase(Locale.ROOT).contains(filter)
                || signature.toLowerCase(Locale.ROOT).contains(filter);
    }

    private static JsonObject error(String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }

    private static String safeDisplay(Object value) {
        try {
            String display = String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
            return display.length() <= 200 ? display : display.substring(0, 197) + "...";
        } catch (Throwable ignored) {
            return "<toString failed>";
        }
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String requireText(String value, String name) throws ReflectionException {
        if (value == null || value.trim().isEmpty()) throw new ReflectionException("invalid_argument", name + " is required.");
        return value.trim();
    }

    private static JsonElement required(JsonObject object, String name) throws ReflectionException {
        if (!object.has(name)) throw new ReflectionException("invalid_argument", name + " is required.");
        return object.get(name);
    }

    private static String requiredString(JsonObject object, String name) throws ReflectionException {
        JsonElement value = required(object, name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().trim().isEmpty()) {
            throw new ReflectionException("invalid_argument", name + " must be a non-empty string.");
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String name, String fallback) throws ReflectionException {
        if (!object.has(name)) return fallback;
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ReflectionException("invalid_argument", name + " must be a string.");
        }
        return value.getAsString();
    }

    private static String optionalNullableString(JsonObject object, String name) throws ReflectionException {
        if (!object.has(name) || object.get(name).isJsonNull()) return null;
        return optionalString(object, name, null);
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean fallback) throws ReflectionException {
        if (!object.has(name)) return fallback;
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ReflectionException("invalid_argument", name + " must be a boolean.");
        }
        return value.getAsBoolean();
    }

    private static int boundedInt(JsonObject object, String name, int fallback, int minimum, int maximum)
            throws ReflectionException {
        if (!object.has(name)) return fallback;
        try {
            int value = object.get(name).getAsInt();
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (RuntimeException e) {
            throw new ReflectionException("invalid_argument", name + " must be between " + minimum + " and " + maximum + ".", e);
        }
    }

    private static JsonArray optionalArray(JsonObject object, String name) throws ReflectionException {
        JsonArray result = optionalNullableArray(object, name);
        return result == null ? new JsonArray() : result;
    }

    private static JsonArray optionalNullableArray(JsonObject object, String name) throws ReflectionException {
        if (!object.has(name) || object.get(name).isJsonNull()) return null;
        if (!object.get(name).isJsonArray()) throw new ReflectionException("invalid_argument", name + " must be an array.");
        return object.getAsJsonArray(name);
    }

    private static int requiredIndex(JsonObject object) throws ReflectionException {
        if (!object.has("index")) throw new ReflectionException("invalid_argument", "index is required.");
        try {
            return object.get("index").getAsInt();
        } catch (RuntimeException e) {
            throw new ReflectionException("invalid_argument", "index must be an integer.", e);
        }
    }

    private static boolean isHandle(JsonElement value) {
        return value != null && value.isJsonObject() && value.getAsJsonObject().has("$handle")
                && value.getAsJsonObject().get("$handle").isJsonPrimitive();
    }

    private static String handleId(JsonElement value) throws ReflectionException {
        if (!isHandle(value)) throw new ReflectionException("handle_required", "Expected an object handle.");
        return value.getAsJsonObject().get("$handle").getAsString();
    }

    private static final class Conversion {
        private final Object value;
        private final int score;

        private Conversion(Object value, int score) {
            this.value = value;
            this.score = score;
        }
    }

    private static final class ConvertedArguments {
        private final Object[] values;
        private final int score;

        private ConvertedArguments(Object[] values, int score) {
            this.values = values;
            this.score = score;
        }
    }

    private static final class MethodSelection {
        private final Method method;
        private final ConvertedArguments arguments;

        private MethodSelection(Method method, ConvertedArguments arguments) {
            this.method = method;
            this.arguments = arguments;
        }
    }

    private static final class ConstructorSelection {
        private final Constructor<?> constructor;
        private final ConvertedArguments arguments;

        private ConstructorSelection(Constructor<?> constructor, ConvertedArguments arguments) {
            this.constructor = constructor;
            this.arguments = arguments;
        }
    }
}
