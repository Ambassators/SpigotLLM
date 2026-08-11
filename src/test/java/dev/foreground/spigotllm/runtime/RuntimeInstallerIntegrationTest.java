package dev.foreground.spigotllm.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RuntimeInstallerIntegrationTest {
    private static final String RELEASES = "https://downloads.claude.ai/claude-code-releases";

    @Test
    @Tag("integration")
    void acceptsTheCurrentAnthropicSignedManifest() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("spigotllm.integration"));
        String version = new String(read(RELEASES + "/stable"), StandardCharsets.UTF_8).trim();
        String manifestUrl = RELEASES + "/" + version + "/manifest.json";
        new RuntimeInstaller(null, 20, 60, 2).verifyClaudeManifest(
                read(manifestUrl),
                read(manifestUrl + ".sig"),
                read("https://downloads.claude.ai/keys/claude-code.asc"));
    }

    private byte[] read(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(20_000);
        InputStream input = connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally {
            input.close();
            connection.disconnect();
        }
    }
}
