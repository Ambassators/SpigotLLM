package dev.foreground.spigotllm.agent.spark;

import dev.foreground.spigotllm.agent.runtime.AgentProtocol;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SparkGuideTest {
    @Test void bundledGuideContainsValidProtocolExamplesForEverySparkOperation() throws Exception {
        String guide = resource("spark-analysis-guide.md");
        Matcher examples = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL).matcher(guide);
        int count = 0;
        while (examples.find()) {
            assertTrue(AgentProtocol.parse(examples.group(1)).getOperation().startsWith("spark."));
            count++;
        }
        assertEquals(6, count);
        for (String operation : new String[]{"spark.status", "spark.snapshot", "spark.command", "spark.report"}) {
            assertTrue(guide.contains("### `" + operation + "`"));
            assertTrue(resource("agent-tools-guide.md").contains(operation));
        }
    }

    @Test void integrationStaysOptionalAndDocumentsUnsupportedFeatures() throws Exception {
        assertTrue(resource("plugin.yml").contains("softdepend: [spark]"));
        String guide = resource("spark-analysis-guide.md");
        for (String boundary : new String[]{"HPROF", "WebSocket", "metadata-only", "global toggles", "not a security sandbox", "spark2json"}) {
            assertTrue(guide.contains(boundary), boundary);
        }
        assertTrue(resource("config.yml").contains("allow-remote-reports: true"));
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = SparkGuideTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Missing bundled resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int read;
            while ((read = in.read(bytes)) != -1) out.write(bytes, 0, read);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
