package dev.foreground.spigotllm.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Generic observe-only Bukkit event subscription with bounded, selected capture. */
public final class StructuredEventWatch implements AutoCloseable {
    private final Plugin plugin;
    private final Listener listener = new Listener() { };
    private final String id;
    private final Class<? extends Event> eventType;
    private final List<String> capture;
    private final List<Filter> filters;
    private final int maxMatches;
    private final Sink sink;
    private final MatchListener matchListener;
    private final AtomicInteger matches = new AtomicInteger();
    private volatile boolean closed;

    public StructuredEventWatch(Plugin plugin, String id, Class<? extends Event> eventType,
                                EventPriority priority, boolean ignoreCancelled,
                                List<String> capture, List<Filter> filters, int maxMatches,
                                Sink sink, MatchListener matchListener) {
        this.plugin = plugin;
        this.id = id;
        this.eventType = eventType;
        this.capture = capture;
        this.filters = filters;
        this.maxMatches = maxMatches <= 0 ? Integer.MAX_VALUE : maxMatches;
        this.sink = sink;
        this.matchListener = matchListener;
        plugin.getServer().getPluginManager().registerEvent(eventType, listener,
                priority == null ? EventPriority.MONITOR : priority, new EventExecutor() {
                    @Override
                    public void execute(Listener ignored, Event event) throws EventException {
                        accept(event);
                    }
                }, plugin, ignoreCancelled);
    }

    public static Class<? extends Event> resolveEvent(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!Event.class.isAssignableFrom(type)) throw new IllegalArgumentException(className + " is not a Bukkit Event.");
            return type.asSubclass(Event.class);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Event class not found: " + className);
        }
    }

    public int matches() {
        return matches.get();
    }

    private void accept(Event event) {
        if (closed || !eventType.isInstance(event)) return;
        try {
            for (Filter filter : filters) {
                Object value = property(event, filter.path);
                if (!filter.matches(value)) return;
            }
            int current;
            do {
                current = matches.get();
                if (current >= maxMatches) return;
            } while (!matches.compareAndSet(current, current + 1));
            int number = current + 1;
            JsonObject record = new JsonObject();
            record.addProperty("type", "event.match");
            record.addProperty("resourceId", id);
            record.addProperty("event", event.getClass().getName());
            record.addProperty("match", number);
            record.addProperty("asynchronous", event.isAsynchronous());
            if (event instanceof Cancellable) record.addProperty("cancelled", ((Cancellable) event).isCancelled());
            JsonObject values = new JsonObject();
            for (String path : capture) {
                try { values.add(path, json(property(event, path), 0)); }
                catch (RuntimeException e) { values.addProperty(path, "<error: " + safe(e.getMessage()) + ">"); }
            }
            record.add("values", values);
            sink.emit(record);
            if (matchListener != null) matchListener.matched(record.deepCopy());
        } catch (RuntimeException e) {
            JsonObject failure = new JsonObject();
            failure.addProperty("type", "event.error");
            failure.addProperty("resourceId", id);
            failure.addProperty("message", safe(e.getMessage()));
            sink.emit(failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(listener);
    }

    static Object property(Object root, String path) {
        if (path == null || path.trim().isEmpty()) return root;
        Object current = root;
        String[] segments = path.split("\\.");
        for (String segment : segments) {
            if (current == null) return null;
            current = member(current, segment);
        }
        return current;
    }

    private static Object member(Object target, String name) {
        if (target instanceof Map) return ((Map<?, ?>) target).get(name);
        if (target.getClass().isArray()) return Array.get(target, Integer.parseInt(name));
        if (target instanceof List) return ((List<?>) target).get(Integer.parseInt(name));
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String[] methods = new String[] {"get" + suffix, "is" + suffix, name};
        for (String candidate : methods) {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(candidate);
                    if (method.getParameterTypes().length != 0) break;
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (NoSuchMethodException ignored) {
                    type = type.getSuperclass();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalArgumentException("Could not read " + name + ": " + e.getMessage(), e);
                }
            }
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Could not read " + name + ": " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("Property not found: " + name + " on " + target.getClass().getName());
    }

    static JsonElement json(Object value, int depth) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Number) return new JsonPrimitive((Number) value);
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Character || value instanceof CharSequence || value instanceof Enum || value instanceof UUID) {
            return new JsonPrimitive(String.valueOf(value));
        }
        if (value instanceof Entity) {
            Entity entity = (Entity) value;
            JsonObject object = new JsonObject();
            object.addProperty("type", entity.getType().name());
            object.addProperty("uuid", entity.getUniqueId().toString());
            object.addProperty("name", entity.getName());
            object.add("location", json(entity.getLocation(), depth + 1));
            return object;
        }
        if (value instanceof World) {
            World world = (World) value;
            JsonObject object = new JsonObject();
            object.addProperty("name", world.getName());
            object.addProperty("uuid", world.getUID().toString());
            return object;
        }
        if (value instanceof Location) {
            Location location = (Location) value;
            JsonObject object = new JsonObject();
            object.addProperty("world", location.getWorld() == null ? null : location.getWorld().getName());
            object.addProperty("x", location.getX());
            object.addProperty("y", location.getY());
            object.addProperty("z", location.getZ());
            object.addProperty("yaw", location.getYaw());
            object.addProperty("pitch", location.getPitch());
            return object;
        }
        if (depth < 2 && value.getClass().isArray()) {
            JsonArray array = new JsonArray();
            int length = Math.min(64, Array.getLength(value));
            for (int index = 0; index < length; index++) array.add(json(Array.get(value, index), depth + 1));
            return array;
        }
        if (depth < 2 && value instanceof Collection) {
            JsonArray array = new JsonArray();
            int count = 0;
            for (Object item : (Collection<?>) value) {
                if (count++ >= 64) break;
                array.add(json(item, depth + 1));
            }
            return array;
        }
        JsonObject object = new JsonObject();
        object.addProperty("class", value.getClass().getName());
        object.addProperty("value", truncate(String.valueOf(value), 512));
        return object;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private static String safe(String value) {
        return value == null ? "Unknown error" : value.replace('\r', ' ').replace('\n', ' ');
    }

    public static final class Filter {
        public final String path;
        public final String operator;
        public final JsonElement expected;
        private final Pattern regex;

        public Filter(String path, String operator, JsonElement expected) {
            this.path = path;
            this.operator = operator == null ? "equals" : operator.toLowerCase(Locale.ROOT);
            this.expected = expected;
            if (!("equals".equals(this.operator) || "equalsignorecase".equals(this.operator)
                    || "contains".equals(this.operator) || "startswith".equals(this.operator)
                    || "endswith".equals(this.operator) || "regex".equals(this.operator)
                    || "exists".equals(this.operator) || "missing".equals(this.operator))) {
                throw new IllegalArgumentException("Unknown filter operator: " + this.operator);
            }
            if (!("exists".equals(this.operator) || "missing".equals(this.operator))
                    && (expected == null || expected.isJsonNull() || !expected.isJsonPrimitive())) {
                throw new IllegalArgumentException("Filter " + this.operator + " requires a primitive value.");
            }
            this.regex = "regex".equals(this.operator) ? Pattern.compile(expected.getAsString()) : null;
        }

        boolean matches(Object actual) {
            if ("exists".equals(operator)) return actual != null;
            if ("missing".equals(operator)) return actual == null;
            String left = String.valueOf(actual);
            String right = expected == null || expected.isJsonNull() ? null : expected.getAsString();
            if ("equals".equals(operator)) return actual == null ? right == null : left.equals(right);
            if ("equalsignorecase".equals(operator)) return actual != null && right != null && left.equalsIgnoreCase(right);
            if ("contains".equals(operator)) return actual != null && right != null && left.contains(right);
            if ("startswith".equals(operator)) return actual != null && right != null && left.startsWith(right);
            if ("endswith".equals(operator)) return actual != null && right != null && left.endsWith(right);
            if ("regex".equals(operator)) return actual != null && regex.matcher(left).matches();
            throw new IllegalArgumentException("Unknown filter operator: " + operator);
        }
    }

    public interface Sink { void emit(JsonObject event); }
    public interface MatchListener { void matched(JsonObject event); }
}
