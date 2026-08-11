package dev.foreground.spigotllm.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foreground.spigotllm.agent.AgentToolRuntime;
import dev.foreground.spigotllm.agent.runtime.AgentRequest;
import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.SessionMode;
import dev.foreground.spigotllm.session.SessionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentConsoleBridgeTest {
    @TempDir Path temporary;

    @Test
    void parsesCompleteSingleLineRequests() {
        AgentConsoleBridge.ParsedRequest request = AgentConsoleBridge.parseRequest("request-1\t/list players\n", 100);
        assertEquals("request-1", request.id);
        assertEquals("list players", request.command);
    }

    @Test
    void waitsForFinalNewlineAndRejectsUnsafeShapes() {
        assertNull(AgentConsoleBridge.parseRequest("request-1\tlist", 100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConsoleBridge.parseRequest("request-1\tlist\nsecond\n", 100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConsoleBridge.parseRequest("bad id\tlist\n", 100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentConsoleBridge.parseRequest("request-1\t123456\n", 5));
    }

    @Test
    void processesOnlyAtomicallyPublishedJsonAndRejectsDuplicateIds() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        AgentConsoleBridge bridge = bridge(runtime, 4096);
        AgentConsoleBridge.Lease lease = bridge.open(Identity.SERVER, agentSession());
        Path requests = lease.responsesDirectory().resolveSibling("requests");
        Path responses = lease.responsesDirectory();
        Path staging = requests.resolve("req-1.json.tmp");
        Files.write(staging, request("req-1").getBytes(StandardCharsets.UTF_8));

        bridge.processJsonRequests(lease);
        assertTrue(runtime.requests.isEmpty(), "a staging file must never be consumed");
        assertTrue(Files.exists(staging));

        Files.move(staging, requests.resolve("req-1.json"));
        bridge.processJsonRequests(lease);
        assertEquals(1, runtime.requests.size());
        assertEquals("req-1", runtime.requests.get(0).getId());
        assertFalse(Files.exists(requests.resolve("req-1.json")));

        AgentConsoleBridge.writeAtomic(requests.resolve("req-1.json"), request("req-1"));
        bridge.processJsonRequests(lease);
        assertEquals(1, runtime.requests.size(), "a duplicate id must not execute twice");
        assertEquals("duplicate_id", errorCode(responses.resolve("req-1.json")));
        assertFalse(Files.exists(responses.resolve("req-1.json.tmp")), "response publication must be atomic");

        bridge.shutdown();
    }

    @Test
    void isolatesEachLeaseGenerationAndAllowsIdsToBeReusedAfterClose() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        AgentConsoleBridge bridge = bridge(runtime, 4096);
        SessionRecord session = agentSession();
        AgentConsoleBridge.Lease first = bridge.open(Identity.SERVER, session);
        Path firstLeaseDirectory = first.responsesDirectory().getParent();
        Path firstRequest = first.responsesDirectory().resolveSibling("requests").resolve("same-id.json");
        AgentConsoleBridge.writeAtomic(firstRequest, request("same-id"));
        bridge.processJsonRequests(first);
        first.close();
        Path staleRequest = firstLeaseDirectory.resolve("requests/stale.json");
        AgentConsoleBridge.writeAtomic(staleRequest, request("stale"));
        bridge.processJsonRequests(first);
        assertTrue(Files.exists(staleRequest), "a closed generation must never be consumed");

        AgentConsoleBridge.Lease second = bridge.open(Identity.SERVER, session);
        Path secondLeaseDirectory = second.responsesDirectory().getParent();
        assertNotEquals(firstLeaseDirectory, secondLeaseDirectory);
        assertTrue(Files.isDirectory(firstLeaseDirectory), "old responses remain available for their generation");
        assertTrue(Files.isDirectory(secondLeaseDirectory));
        assertTrue(isEmpty(second.responsesDirectory()));

        Path secondRequest = second.responsesDirectory().resolveSibling("requests").resolve("same-id.json");
        AgentConsoleBridge.writeAtomic(secondRequest, request("same-id"));
        bridge.processJsonRequests(second);
        assertEquals(2, runtime.requests.size(), "request ids are scoped to one lease generation");
        assertEquals(1, runtime.closedPrompts.size());

        bridge.shutdown();
        assertEquals(2, runtime.closedPrompts.size());
    }

    @Test
    void rejectsMismatchedMalformedAndOversizedQueueFilesWithoutSubmitting() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        AgentConsoleBridge bridge = bridge(runtime, 1024);
        AgentConsoleBridge.Lease lease = bridge.open(Identity.SERVER, agentSession());
        Path requests = lease.responsesDirectory().resolveSibling("requests");
        Path responses = lease.responsesDirectory();

        AgentConsoleBridge.writeAtomic(requests.resolve("filename.json"), request("envelope"));
        AgentConsoleBridge.writeAtomic(requests.resolve("malformed.json"), "{not-json");
        Files.write(requests.resolve("large.json"), new byte[1025]);
        bridge.processJsonRequests(lease);

        assertTrue(runtime.requests.isEmpty());
        assertEquals("id_mismatch", errorCode(responses.resolve("filename.json")));
        assertEquals("invalid_json", errorCode(responses.resolve("malformed.json")));
        assertEquals("request_too_large", errorCode(responses.resolve("large.json")));
        assertTrue(isEmpty(requests));

        bridge.shutdown();
    }

    @Test
    void boundsEachQueuePollToSixteenRequests() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        AgentConsoleBridge bridge = bridge(runtime, 4096);
        AgentConsoleBridge.Lease lease = bridge.open(Identity.SERVER, agentSession());
        Path requests = lease.responsesDirectory().resolveSibling("requests");
        for (int index = 0; index < 17; index++) {
            String id = "request-" + index;
            AgentConsoleBridge.writeAtomic(requests.resolve(id + ".json"), request(id));
        }

        bridge.processJsonRequests(lease);
        assertEquals(16, runtime.requests.size());
        int remaining;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(requests, "*.json")) {
            remaining = 0;
            for (Path ignored : files) remaining++;
        }
        assertEquals(1, remaining);

        bridge.processJsonRequests(lease);
        assertEquals(17, runtime.requests.size());
        bridge.shutdown();
    }

    @Test
    void atomicWriterReplacesCompleteContentsAndLeavesNoTemporaryFile() throws Exception {
        Path target = temporary.resolve("atomic/result.json");
        AgentConsoleBridge.writeAtomic(target, "first");
        AgentConsoleBridge.writeAtomic(target, "second");

        assertEquals("second", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(target.resolveSibling("result.json.tmp")));
    }

    private AgentConsoleBridge bridge(RecordingRuntime runtime, int maxRequestBytes) throws Exception {
        return new AgentConsoleBridge(temporary.resolve("bridge"), temporary.resolve("latest.log"),
                256, maxRequestBytes, runtime);
    }

    private static SessionRecord agentSession() {
        return new SessionRecord("runtime-tests", Provider.CODEX, SessionMode.AGENT);
    }

    private static String request(String id) {
        return "{\"id\":\"" + id + "\",\"operation\":\"snapshot.server\","
                + "\"arguments\":{},\"lifecycle\":\"prompt\"}\n";
    }

    private static String errorCode(Path response) throws Exception {
        JsonObject object = JsonParser.parseString(
                new String(Files.readAllBytes(response), StandardCharsets.UTF_8)).getAsJsonObject();
        return object.getAsJsonObject("error").get("code").getAsString();
    }

    private static boolean isEmpty(Path directory) throws Exception {
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            return !files.iterator().hasNext();
        }
    }

    private static final class RecordingRuntime implements AgentConsoleBridge.RuntimeAdapter {
        final List<AgentRequest> requests = new ArrayList<AgentRequest>();
        final List<String> closedPrompts = new ArrayList<String>();

        @Override public void tick() { }
        @Override public void submit(AgentToolRuntime.LeaseAccess lease, AgentRequest request) {
            requests.add(request);
        }
        @Override public void closePrompt(String owner, String promptScope, String handleOwner) {
            closedPrompts.add(owner + "|" + promptScope + "|" + handleOwner);
        }
    }
}
