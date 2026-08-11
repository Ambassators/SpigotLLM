package dev.foreground.spigotllm.agent.runtime;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentProtocolTest {
    @Test
    void parsesDefaultAndExplicitLifecycles() throws Exception {
        AgentRequest defaulted = AgentProtocol.parse(
                "{\"id\":\"req-1\",\"operation\":\"reflect.root\",\"arguments\":{}}"
        );
        assertEquals("req-1", defaulted.getId());
        assertEquals("reflect.root", defaulted.getOperation());
        assertEquals(Lifecycle.prompt(), defaulted.getLifecycle());

        AgentRequest ttl = AgentProtocol.parse(
                "{\"id\":\"req_2\",\"operation\":\"code.runLater\",\"arguments\":{},"
                        + "\"lifecycle\":{\"type\":\"ttl\",\"seconds\":45}}"
        );
        assertEquals(Lifecycle.ttlSeconds(45), ttl.getLifecycle());

        AgentRequest persistent = AgentProtocol.parse(
                "{\"id\":\"req3\",\"operation\":\"module.install\",\"arguments\":{},"
                        + "\"lifecycle\":\"persistent\"}"
        );
        assertEquals(Lifecycle.persistent(), persistent.getLifecycle());
    }

    @Test
    void requestArgumentsAreDefensiveCopies() throws Exception {
        AgentRequest request = AgentProtocol.parse(
                "{\"id\":\"req\",\"operation\":\"code.run\",\"arguments\":{\"source\":\"x\"}}"
        );
        JsonObject first = request.getArguments();
        first.addProperty("source", "changed");
        assertEquals("x", request.getArguments().get("source").getAsString());
    }

    @Test
    void rejectsMalformedEnvelopesAndUnsafeDurations() {
        assertCode("invalid_json", "not-json");
        assertCode("invalid_envelope", "[]");
        assertCode("invalid_id", "{\"id\":\"bad/id\",\"operation\":\"code.run\",\"arguments\":{}}");
        assertCode("invalid_operation", "{\"id\":\"ok\",\"operation\":\"Bad operation\",\"arguments\":{}}");
        assertCode("invalid_arguments", "{\"id\":\"ok\",\"operation\":\"code.run\",\"arguments\":[]}");
        assertCode("invalid_lifecycle", "{\"id\":\"ok\",\"operation\":\"code.run\",\"arguments\":{},"
                + "\"lifecycle\":{\"type\":\"ttl\",\"seconds\":86401}}");
        assertCode("invalid_lifecycle", "{\"id\":\"ok\",\"operation\":\"code.run\",\"arguments\":{},"
                + "\"lifecycle\":{\"type\":\"ttl\",\"seconds\":1.5}}");
    }

    @Test
    void enforcesUtf8ByteLimitAndEncoding() {
        byte[] tooLarge = new byte[AgentProtocol.MAX_REQUEST_BYTES + 1];
        ProtocolException size = assertThrows(ProtocolException.class, () -> AgentProtocol.parse(tooLarge));
        assertEquals("request_too_large", size.getCode());

        ProtocolException encoding = assertThrows(ProtocolException.class,
                () -> AgentProtocol.parse(new byte[]{(byte) 0xC3, (byte) 0x28}));
        assertEquals("invalid_encoding", encoding.getCode());

        String prefix = "{\"id\":\"ok\",\"operation\":\"code.run\",\"arguments\":{\"v\":\"";
        String suffix = "\"}}";
        int fixedBytes = (prefix + suffix).getBytes(StandardCharsets.UTF_8).length;
        int characterCount = ((AgentProtocol.MAX_REQUEST_BYTES - fixedBytes) / 2) + 1;
        StringBuilder content = new StringBuilder(prefix.length() + characterCount + suffix.length());
        content.append(prefix);
        for (int index = 0; index < characterCount; index++) content.append('é');
        content.append(suffix);
        ProtocolException unicodeSize = assertThrows(ProtocolException.class,
                () -> AgentProtocol.parse(content.toString()));
        assertEquals("request_too_large", unicodeSize.getCode());
    }

    @Test
    void buildsStableSafeResponses() {
        JsonObject source = new JsonObject();
        source.addProperty("value", 4);
        JsonObject success = AgentProtocol.success("req", source);
        source.addProperty("value", 9);
        assertEquals("success", success.get("status").getAsString());
        assertEquals(4, success.getAsJsonObject("result").get("value").getAsInt());

        JsonObject error = AgentProtocol.error("unsafe/id", "NOT SAFE", "bad\r\nmessage");
        assertTrue(error.get("id").isJsonNull());
        assertEquals("internal_error", error.getAsJsonObject("error").get("code").getAsString());
        assertEquals("bad  message", error.getAsJsonObject("error").get("message").getAsString());

        JsonObject nullResult = AgentProtocol.success("req", null);
        assertTrue(nullResult.get("result").isJsonNull());
    }

    private static void assertCode(String expected, String json) {
        ProtocolException failure = assertThrows(ProtocolException.class, () -> AgentProtocol.parse(json));
        assertEquals(expected, failure.getCode());
    }
}
