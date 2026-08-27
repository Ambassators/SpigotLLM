package dev.foreground.spigotllm.session;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.ReasoningEffort;
import dev.foreground.spigotllm.model.SessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void managesNamedPersistentSessions() throws Exception {
        SessionStore store = new SessionStore(temporaryDirectory.resolve("identities"));
        SessionRecord defaultSession = store.getOrCreateActive(Identity.SERVER, Provider.CODEX, SessionMode.CHAT);
        assertEquals("default-chat", defaultSession.getName());

        SessionRecord feature = store.create(Identity.SERVER, Provider.CODEX, SessionMode.CHAT, "feature_one");
        store.updateProviderId(Identity.SERVER, feature, "provider-thread-id");
        assertEquals("provider-thread-id", store.getOrCreateActive(
                Identity.SERVER, Provider.CODEX, SessionMode.CHAT).getProviderSessionId());
        assertTrue(store.rename(Identity.SERVER, Provider.CODEX, SessionMode.CHAT, "feature_one", "renamed"));
        assertEquals("renamed", store.getOrCreateActive(Identity.SERVER, Provider.CODEX, SessionMode.CHAT).getName());
        assertFalse(store.use(Identity.SERVER, Provider.CLAUDE, SessionMode.CHAT, "renamed"));
        assertNotNull(store.delete(Identity.SERVER, Provider.CODEX, SessionMode.CHAT, "renamed"));
        assertNull(store.delete(Identity.SERVER, Provider.CODEX, SessionMode.CHAT, "renamed"));

        SessionStore reloaded = new SessionStore(temporaryDirectory.resolve("identities"));
        assertEquals(1, reloaded.list(Identity.SERVER).size());
    }

    @Test
    void renamingDoesNotMoveClaudeProviderStorage() throws Exception {
        SessionStore store = new SessionStore(temporaryDirectory.resolve("identities"));
        SessionRecord session = store.create(Identity.SERVER, Provider.CLAUDE, SessionMode.AGENT, "before");
        Path before = store.claudeSessionRoot(Identity.SERVER, session);
        assertTrue(store.rename(Identity.SERVER, Provider.CLAUDE, SessionMode.AGENT, "before", "after"));
        assertEquals(before, store.claudeSessionRoot(Identity.SERVER, session));
    }

    @Test
    void persistsEffortOnEachNamedSession() throws Exception {
        Path root = temporaryDirectory.resolve("identities");
        SessionStore store = new SessionStore(root);
        SessionRecord session = store.create(Identity.SERVER, Provider.CODEX, SessionMode.AGENT, "deep-work");
        store.updateReasoningEffort(Identity.SERVER, session, ReasoningEffort.XHIGH);

        SessionStore reloaded = new SessionStore(root);
        SessionRecord active = reloaded.getOrCreateActive(Identity.SERVER, Provider.CODEX, SessionMode.AGENT);
        assertEquals(ReasoningEffort.XHIGH, active.getReasoningEffort());

        reloaded.updateReasoningEffort(Identity.SERVER, active, ReasoningEffort.DEFAULT);
        SessionStore reset = new SessionStore(root);
        assertEquals(ReasoningEffort.DEFAULT, reset.getOrCreateActive(
                Identity.SERVER, Provider.CODEX, SessionMode.AGENT).getReasoningEffort());
    }

    @Test
    void validatesFilesystemSafeNames() {
        assertTrue(SessionStore.validName("abc-123_test"));
        assertFalse(SessionStore.validName("../escape"));
        assertFalse(SessionStore.validName("contains spaces"));
    }

    @Test
    void autoNamesCodexAgentThreadsFromTheirFirstPrompt() throws Exception {
        Path root = temporaryDirectory.resolve("identities");
        SessionStore store = new SessionStore(root);

        SessionRecord first = store.getOrCreateActive(Identity.SERVER, Provider.CODEX, SessionMode.AGENT);
        assertTrue(first.isAutoNamingPending());
        assertTrue(first.getName().startsWith("new-"));

        SessionRecord named = store.prepareCodexAgentPrompt(
                Identity.SERVER, "Please fix the server startup crash!");
        assertEquals("please-fix-the-server-startup-cr", named.getName());
        assertEquals("Please fix the server startup crash!", named.getDisplayName());
        assertFalse(named.isAutoNamingPending());
        assertEquals(named.getName(), store.getOrCreateActive(
                Identity.SERVER, Provider.CODEX, SessionMode.AGENT).getName());

        SessionRecord second = store.createCodexAgentThread(Identity.SERVER);
        assertTrue(second.isAutoNamingPending());
        assertEquals("please-fix-the-server-startup-2", store.prepareCodexAgentPrompt(
                Identity.SERVER, "Please fix the server startup crash!").getName());

        SessionStore reloaded = new SessionStore(root);
        SessionRecord reloadedActive = reloaded.getOrCreateActive(
                Identity.SERVER, Provider.CODEX, SessionMode.AGENT);
        assertEquals("please-fix-the-server-startup-2", reloadedActive.getName());
        assertEquals("Please fix the server startup crash!", reloadedActive.getDisplayName());
    }
}
