package dev.foreground.spigotllm.agent.spark;

import com.google.gson.JsonObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Loads official full JSON exports or confined local spark2json exports; never arbitrary URLs. */
final class SparkReportSource {
    private final Path root;
    private final boolean remoteEnabled;
    private final int maxBytes;
    private final int timeoutMillis;

    SparkReportSource(Path root, boolean remoteEnabled, int maxBytes, int timeoutMillis) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
        this.remoteEnabled = remoteEnabled;
        this.maxBytes = Math.max(1024, Math.min(64 * 1024 * 1024, maxBytes));
        this.timeoutMillis = Math.max(1000, Math.min(60000, timeoutMillis));
    }

    JsonObject load(JsonObject args) throws IOException {
        boolean file = args.has("file"), url = args.has("url");
        if (file == url) throw new SparkException("spark_invalid_source", "Supply exactly one of url (official viewer link/code) or file (relative JSON import path).");
        if (file) {
            Path path = resolveFile(SparkJson.text(args, "file", ""));
            if (Files.size(path) > maxBytes) throw tooLarge();
            try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                return parse(readBounded(in, maxBytes));
            }
        }
        if (!remoteEnabled) throw new SparkException("spark_remote_disabled", "Remote Spark reports are disabled; import a local JSON export instead.");
        URI endpoint = canonicalUrl(SparkJson.text(args, "url", ""));
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("User-Agent", "SpigotLLM-Spark/1.0");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new SparkException("spark_http_error", "Official Spark export returned HTTP " + status + "; redirects are not followed.");
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals("application/json")) {
                throw new SparkException("spark_invalid_source", "Expected Spark JSON, not an HTML viewer page or binary profile. Use a full raw export.");
            }
            if (connection.getContentLengthLong() > maxBytes) throw tooLarge();
            String encoding = connection.getContentEncoding();
            try (InputStream raw = new LimitInputStream(connection.getInputStream(), maxBytes)) {
                if (encoding != null && !"identity".equalsIgnoreCase(encoding) && !"gzip".equalsIgnoreCase(encoding)) {
                    throw new SparkException("spark_invalid_source", "Unsupported response encoding.");
                }
                InputStream decoded = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw;
                try (InputStream body = decoded) { return parse(readBounded(body, maxBytes)); }
            }
        } finally { connection.disconnect(); }
    }

    private JsonObject parse(byte[] bytes) throws IOException {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            return SparkJson.parse(reader);
        } catch (SparkException e) { throw e; }
        catch (IOException | RuntimeException e) { throw new SparkException("spark_invalid_data", "Malformed Spark JSON export: " + e.getClass().getSimpleName()); }
    }

    Path resolveFile(String name) throws IOException {
        if (name.isEmpty() || !name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new SparkException("spark_invalid_source", "Import a .json file produced by spark2json. Binary .sparkprofile, .sparkheap, and HPROF files are not JSON.");
        }
        Path relative;
        try { relative = Paths.get(name); }
        catch (InvalidPathException e) { throw new SparkException("spark_invalid_source", "Invalid import path."); }
        if (relative.isAbsolute()) throw new SparkException("spark_invalid_source", "Import paths must be relative to the report folder.");
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) throw new SparkException("spark_invalid_source", "Import path escapes the report folder.");
        Path real = candidate.toRealPath();
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new SparkException("spark_invalid_source", "Import must be a regular file inside the report folder, not an external symlink.");
        }
        return real;
    }

    static URI canonicalUrl(String input) {
        String value = input.trim();
        if (value.length() > 2048) throw new SparkException("spark_invalid_source", "Report link is too long.");
        if (value.matches("[A-Za-z0-9_-]{1,128}")) value = "https://spark.lucko.me/" + value;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"spark.lucko.me".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_-]{1,128}/?")) {
                throw new SparkException("spark_invalid_source", "Only official https://spark.lucko.me/<report-code> links or report codes are accepted.");
            }
            String code = uri.getRawPath().substring(1).replace("/", "");
            // Ignore viewer query/fragment state. Thread/window filters are explicit tool arguments.
            return new URI("https://spark.lucko.me/" + code + "?raw=1&full=true");
        } catch (URISyntaxException e) { throw new SparkException("spark_invalid_source", "Malformed Spark report link."); }
    }

    static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(max, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Spark report read cancelled.");
            if (count > max - result.size()) throw tooLarge();
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private static SparkException tooLarge() { return new SparkException("spark_report_too_large", "Report exceeds the configured byte limit (applied before and after decompression). Reduce the capture or use a narrower export."); }

    private static final class LimitInputStream extends FilterInputStream {
        private final int limit;
        private int count;
        LimitInputStream(InputStream in, int limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++count > limit) throw tooLarge();
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int read = in.read(b, off, len);
            if (read > 0) {
                if (read > limit - count) throw tooLarge();
                count += read;
            }
            return read;
        }
    }
}
