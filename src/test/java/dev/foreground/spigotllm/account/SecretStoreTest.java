package dev.foreground.spigotllm.account;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SecretStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void encryptsAndRemovesProviderCredentials() throws Exception {
        String credential = "sk-ant-test-only-credential-123456789";
        SecretStore store = new SecretStore(temporaryDirectory);
        store.initialize();
        store.put(Identity.SERVER, Provider.CLAUDE, credential);

        assertTrue(store.has(Identity.SERVER, Provider.CLAUDE));
        assertEquals(credential, store.get(Identity.SERVER, Provider.CLAUDE));
        Path encrypted = temporaryDirectory.resolve("identities/server/credentials/claude.secret");
        assertFalse(new String(Files.readAllBytes(encrypted), StandardCharsets.UTF_8).contains(credential));
        assertNull(store.get(Identity.SERVER, Provider.CODEX));

        store.remove(Identity.SERVER, Provider.CLAUDE);
        assertFalse(store.has(Identity.SERVER, Provider.CLAUDE));
    }
}
