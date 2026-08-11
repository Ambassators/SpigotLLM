package dev.foreground.spigotllm.agent.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Parser and response builders for the lease JSON request protocol. */
public final class AgentProtocol {
    public static final int MAX_REQUEST_BYTES = 256 * 1024;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 4096;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern OPERATION = Pattern.compile("[a-z][A-Za-z0-9_.-]{0,127}");
    private static final Pattern ERROR_CODE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private AgentProtocol() {
    }

    public static AgentRequest parse(byte[] bytes) throws ProtocolException {
        if (bytes == null) throw protocolError("invalid_request", "Request body is required.", null);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw protocolError("request_too_large", "Request exceeds the 256 KiB limit.", null);
        }
        final String json;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            json = decoded.toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw protocolError("invalid_encoding", "Request must be valid UTF-8.", null);
        }
        return parseValidated(json);
    }

    public static AgentRequest parse(String json) throws ProtocolException {
        if (json == null) throw protocolError("invalid_request", "Request body is required.", null);
        byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_REQUEST_BYTES) {
            throw protocolError("request_too_large", "Request exceeds the 256 KiB limit.", null);
        }
        return parseValidated(json);
    }

    private static AgentRequest parseValidated(String json) throws ProtocolException {
        final JsonElement parsed;
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            parsed = JsonParser.parseReader(reader);
        } catch (JsonParseException malformed) {
            throw protocolError("invalid_json", "Request is not valid JSON.", null);
        } catch (RuntimeException malformed) {
            throw protocolError("invalid_json", "Request is not valid JSON.", null);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw protocolError("invalid_envelope", "Request must be a JSON object.", null);
        }

        JsonObject envelope = parsed.getAsJsonObject();
        String id = string(envelope, "id", null);
        if (id == null || !REQUEST_ID.matcher(id).matches()) {
            throw protocolError("invalid_id", "Request id must match [A-Za-z0-9_-]{1,64}.", null);
        }
        String operation = string(envelope, "operation", id);
        if (operation == null || !OPERATION.matcher(operation).matches()) {
            throw protocolError("invalid_operation", "Operation name is invalid.", id);
        }

        JsonElement argumentsElement = envelope.get("arguments");
        if (argumentsElement == null || !argumentsElement.isJsonObject()) {
            throw protocolError("invalid_arguments", "Arguments must be a JSON object.", id);
        }
        Lifecycle lifecycle = parseLifecycle(envelope.get("lifecycle"), id);
        return new AgentRequest(id, operation, argumentsElement.getAsJsonObject(), lifecycle);
    }

    private static Lifecycle parseLifecycle(JsonElement value, String id) throws ProtocolException {
        if (value == null || value.isJsonNull()) return Lifecycle.prompt();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            try {
                return Lifecycle.named(value.getAsString());
            } catch (IllegalArgumentException invalid) {
                throw protocolError("invalid_lifecycle", invalid.getMessage(), id);
            }
        }
        if (!value.isJsonObject()) {
            throw protocolError("invalid_lifecycle", "Lifecycle must be a name or object.", id);
        }

        JsonObject object = value.getAsJsonObject();
        String type = string(object, "type", id);
        if (type == null) throw protocolError("invalid_lifecycle", "Lifecycle type is required.", id);
        if (!"ttl".equalsIgnoreCase(type)) {
            try {
                return Lifecycle.named(type);
            } catch (IllegalArgumentException invalid) {
                throw protocolError("invalid_lifecycle", invalid.getMessage(), id);
            }
        }

        boolean millisPresent = object.has("ttlMillis");
        boolean secondsPresent = object.has("ttlSeconds") || object.has("seconds");
        if (millisPresent == secondsPresent) {
            throw protocolError("invalid_lifecycle",
                    "TTL lifecycle requires exactly one of ttlMillis, ttlSeconds, or seconds.", id);
        }
        try {
            if (millisPresent) return Lifecycle.ttlMillis(exactLong(object.get("ttlMillis")));
            JsonElement seconds = object.has("ttlSeconds") ? object.get("ttlSeconds") : object.get("seconds");
            return Lifecycle.ttlSeconds(exactLong(seconds));
        } catch (IllegalArgumentException invalid) {
            throw protocolError("invalid_lifecycle", invalid.getMessage(), id);
        }
    }

    private static long exactLong(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("TTL duration must be an integer.");
        }
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("TTL duration must be an integer.");
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("TTL duration must be an integer.");
        }
    }

    private static String string(JsonObject object, String name, String id) throws ProtocolException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    public static JsonObject success(String id, JsonElement result) {
        JsonObject response = base(id, "success");
        response.add("result", copyOrNull(result));
        return response;
    }

    public static JsonObject success(AgentRequest request, JsonElement result) {
        if (request == null) throw new IllegalArgumentException("Request is required.");
        return success(request.getId(), result);
    }

    public static JsonObject error(ProtocolException failure) {
        if (failure == null) return error(null, "internal_error", "Unknown protocol failure.");
        return error(failure.getRequestId(), failure.getCode(), failure.getMessage());
    }

    public static JsonObject error(String id, String code, String message) {
        JsonObject response = base(id, "error");
        JsonObject problem = new JsonObject();
        problem.addProperty("code", safeCode(code));
        problem.addProperty("message", safeMessage(message));
        response.add("error", problem);
        return response;
    }

    public static JsonObject error(String id, String code, String message, JsonElement details) {
        JsonObject response = error(id, code, message);
        if (details != null && !details.isJsonNull()) {
            response.getAsJsonObject("error").add("details", details.deepCopy());
        }
        return response;
    }

    private static JsonObject base(String id, String status) {
        JsonObject response = new JsonObject();
        if (id != null && REQUEST_ID.matcher(id).matches()) response.addProperty("id", id);
        else response.add("id", JsonNull.INSTANCE);
        response.addProperty("status", status);
        return response;
    }

    private static JsonElement copyOrNull(JsonElement value) {
        return value == null ? JsonNull.INSTANCE : value.deepCopy();
    }

    private static String safeCode(String code) {
        return code != null && ERROR_CODE.matcher(code).matches() ? code : "internal_error";
    }

    private static String safeMessage(String message) {
        if (message == null || message.trim().isEmpty()) return "Unknown error.";
        StringBuilder safe = new StringBuilder(Math.min(message.length(), MAX_ERROR_MESSAGE_LENGTH));
        for (int index = 0; index < message.length() && safe.length() < MAX_ERROR_MESSAGE_LENGTH; index++) {
            char character = message.charAt(index);
            safe.append(Character.isISOControl(character) ? ' ' : character);
        }
        return safe.toString().trim();
    }

    private static ProtocolException protocolError(String code, String message, String id) {
        return new ProtocolException(code, message, id);
    }
}
