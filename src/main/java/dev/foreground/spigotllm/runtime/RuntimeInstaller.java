package dev.foreground.spigotllm.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.provider.ProgressListener;
import dev.foreground.spigotllm.util.JsonFiles;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class RuntimeInstaller {
    private static final String CODEX_CHANNEL = "https://releases.openai.com/codex/channels/latest";
    private static final String CLAUDE_RELEASES = "https://downloads.claude.ai/claude-code-releases";
    private static final String CLAUDE_SIGNING_KEY = "https://downloads.claude.ai/keys/claude-code.asc";
    private static final String CLAUDE_SIGNING_FINGERPRINT = "31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE";

    private final RuntimeResolver resolver;
    private final int connectTimeoutMillis;
    private final int downloadTimeoutMillis;
    private final int retainReleases;

    public RuntimeInstaller(RuntimeResolver resolver, int connectTimeoutSeconds,
                            int downloadTimeoutSeconds, int retainReleases) {
        this.resolver = resolver;
        this.connectTimeoutMillis = Math.max(5, connectTimeoutSeconds) * 1000;
        this.downloadTimeoutMillis = Math.max(60, downloadTimeoutSeconds) * 1000;
        this.retainReleases = Math.max(2, retainReleases);
    }

    public synchronized String install(Provider provider, ProgressListener progress) throws IOException {
        Platform platform = Platform.detect();
        progress.onProgress("Resolving the official " + provider.id() + " stable release...");
        Release release = provider == Provider.CODEX
                ? resolveCodex(platform)
                : resolveClaude(platform);

        Path providerRoot = resolver.root(provider);
        Path releasesRoot = providerRoot.resolve("releases");
        Files.createDirectories(releasesRoot);
        Path target = releasesRoot.resolve(release.version).normalize();
        if (!target.startsWith(releasesRoot.normalize())) {
            throw new IOException("Invalid release version returned by provider");
        }

        if (!Files.isDirectory(target)) {
            Path staging = providerRoot.resolve("staging-" + UUID.randomUUID().toString()).normalize();
            Files.createDirectories(staging);
            try {
                progress.onProgress("Downloading " + provider.id() + " " + release.version + "...");
                if (provider == Provider.CODEX) {
                    Path archive = staging.resolve("runtime.tar.gz");
                    download(release.url, archive);
                    verifySha256(archive, release.sha256);
                    Path unpacked = staging.resolve("unpacked");
                    extractTarGzip(archive, unpacked);
                    moveDirectory(unpacked, target);
                } else {
                    Path unpacked = staging.resolve("unpacked");
                    Files.createDirectories(unpacked);
                    Path executable = unpacked.resolve(platform.executableName(provider));
                    download(release.url, executable);
                    verifySha256(executable, release.sha256);
                    makeExecutable(executable);
                    moveDirectory(unpacked, target);
                }
            } finally {
                deleteTree(staging);
            }
        } else {
            progress.onProgress(provider.id() + " " + release.version + " is already downloaded; verifying activation...");
        }

        Path executable = provider == Provider.CODEX
                ? target.resolve("bin").resolve(platform.executableName(provider))
                : target.resolve(platform.executableName(provider));
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Release did not contain expected executable: " + executable);
        }
        makeExecutable(executable);
        smokeTest(executable, release.version);
        activate(provider, release.version, executable);
        prune(provider, release.version);
        progress.onProgress("Activated " + provider.id() + " " + release.version + ".");
        return release.version;
    }

    public synchronized String rollback(Provider provider) throws IOException {
        RuntimeMetadata metadata = resolver.metadata(provider);
        if (metadata == null || metadata.previousVersion == null || metadata.previousExecutable == null) {
            throw new IOException("No previous " + provider.id() + " runtime is available");
        }
        Path previous = resolver.root(provider).resolve(metadata.previousExecutable).normalize();
        if (!previous.startsWith(resolver.root(provider).normalize()) || !Files.isRegularFile(previous)) {
            throw new IOException("Previous runtime executable is missing");
        }
        String oldCurrentVersion = metadata.currentVersion;
        String oldCurrentExecutable = metadata.currentExecutable;
        metadata.currentVersion = metadata.previousVersion;
        metadata.currentExecutable = metadata.previousExecutable;
        metadata.previousVersion = oldCurrentVersion;
        metadata.previousExecutable = oldCurrentExecutable;
        metadata.installedAt = System.currentTimeMillis();
        JsonFiles.writeAtomic(resolver.metadataPath(provider), metadata);
        return metadata.currentVersion;
    }

    public String status(Provider provider) throws IOException {
        RuntimeMetadata metadata = resolver.metadata(provider);
        if (metadata == null) {
            return provider.id() + ": not installed";
        }
        return provider.id() + ": " + metadata.currentVersion
                + (metadata.previousVersion == null ? "" : " (rollback: " + metadata.previousVersion + ")");
    }

    private Release resolveCodex(Platform platform) throws IOException {
        JsonObject root = readJson(CODEX_CHANNEL);
        String tag = string(root, "tag_name");
        String version = tag.startsWith("rust-v") ? tag.substring("rust-v".length()) : tag;
        validateVersion(version);
        String wanted = platform.assetName(Provider.CODEX);
        JsonArray assets = root.getAsJsonArray("assets");
        if (assets != null) {
            for (JsonElement element : assets) {
                JsonObject asset = element.getAsJsonObject();
                if (wanted.equals(string(asset, "name"))) {
                    String digest = string(asset, "digest");
                    if (!digest.startsWith("sha256:")) {
                        throw new IOException("Codex release asset did not include a SHA-256 digest");
                    }
                    return new Release(version, string(asset, "browser_download_url"), digest.substring(7));
                }
            }
        }
        throw new IOException("No Codex asset is published for " + wanted);
    }

    private Release resolveClaude(Platform platform) throws IOException {
        String version = readText(CLAUDE_RELEASES + "/stable").trim();
        validateVersion(version);
        String manifestUrl = CLAUDE_RELEASES + "/" + version + "/manifest.json";
        byte[] manifestBytes = readBytes(manifestUrl);
        byte[] signatureBytes = readBytes(manifestUrl + ".sig");
        verifyClaudeManifest(manifestBytes, signatureBytes, readBytes(CLAUDE_SIGNING_KEY));
        JsonObject manifest = new JsonParser().parse(
                new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject platforms = manifest.getAsJsonObject("platforms");
        JsonObject entry = platforms == null ? null : platforms.getAsJsonObject(platform.assetName(Provider.CLAUDE));
        if (entry == null) {
            throw new IOException("No Claude asset is published for " + platform.assetName(Provider.CLAUDE));
        }
        String binary = string(entry, "binary");
        String url = CLAUDE_RELEASES + "/" + version + "/" + platform.assetName(Provider.CLAUDE) + "/" + binary;
        return new Release(version, url, string(entry, "checksum"));
    }

    private void activate(Provider provider, String version, Path executable) throws IOException {
        RuntimeMetadata old = resolver.metadata(provider);
        RuntimeMetadata metadata = new RuntimeMetadata();
        metadata.currentVersion = version;
        metadata.currentExecutable = resolver.root(provider).relativize(executable).toString();
        metadata.installedAt = System.currentTimeMillis();
        if (old != null && old.currentVersion != null && !old.currentVersion.equals(version)) {
            metadata.previousVersion = old.currentVersion;
            metadata.previousExecutable = old.currentExecutable;
        } else if (old != null) {
            metadata.previousVersion = old.previousVersion;
            metadata.previousExecutable = old.previousExecutable;
        }
        JsonFiles.writeAtomic(resolver.metadataPath(provider), metadata);
    }

    private void prune(Provider provider, String currentVersion) throws IOException {
        RuntimeMetadata metadata = resolver.metadata(provider);
        Path root = resolver.root(provider).resolve("releases");
        if (!Files.isDirectory(root)) return;
        List<Path> releases = new ArrayList<Path>();
        java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        try {
            for (Path path : stream) if (Files.isDirectory(path)) releases.add(path);
        } finally {
            stream.close();
        }
        Collections.sort(releases, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                try {
                    return Files.getLastModifiedTime(right).compareTo(Files.getLastModifiedTime(left));
                } catch (IOException ignored) {
                    return 0;
                }
            }
        });
        int kept = 0;
        for (Path release : releases) {
            String version = release.getFileName().toString();
            boolean protectedRelease = version.equals(currentVersion)
                    || (metadata != null && version.equals(metadata.previousVersion));
            if (protectedRelease || kept < retainReleases) {
                kept++;
            } else {
                deleteTree(release);
            }
        }
    }

    private void download(String url, Path output) throws IOException {
        HttpURLConnection connection = open(url);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Download returned HTTP " + status + " for " + url);
        }
        Files.createDirectories(output.getParent());
        InputStream input = new BufferedInputStream(connection.getInputStream());
        OutputStream target = Files.newOutputStream(output);
        byte[] buffer = new byte[64 * 1024];
        try {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Download interrupted");
                target.write(buffer, 0, read);
            }
        } finally {
            try { target.close(); } finally { input.close(); connection.disconnect(); }
        }
    }

    private JsonObject readJson(String url) throws IOException {
        return new JsonParser().parse(readText(url)).getAsJsonObject();
    }

    private String readText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " for " + url);
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        } finally {
            reader.close();
            connection.disconnect();
        }
        return output.toString();
    }

    private byte[] readBytes(String url) throws IOException {
        HttpURLConnection connection = open(url);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " for " + url);
        InputStream input = connection.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        } finally {
            input.close();
            connection.disconnect();
        }
    }

    void verifyClaudeManifest(byte[] manifest, byte[] detachedSignature, byte[] armoredKey)
            throws IOException {
        try {
            BouncyCastleProvider provider = new BouncyCastleProvider();
            PGPObjectFactory signatures = new PGPObjectFactory(
                    PGPUtil.getDecoderStream(new ByteArrayInputStream(detachedSignature)),
                    new JcaKeyFingerprintCalculator());
            Object object = signatures.nextObject();
            if (object instanceof PGPCompressedData) {
                object = new PGPObjectFactory(
                        ((PGPCompressedData) object).getDataStream(),
                        new JcaKeyFingerprintCalculator()).nextObject();
            }
            if (!(object instanceof PGPSignatureList) || ((PGPSignatureList) object).isEmpty()) {
                throw new IOException("Claude manifest did not contain a detached OpenPGP signature");
            }
            PGPSignature signature = ((PGPSignatureList) object).get(0);
            PGPPublicKeyRingCollection rings = new PGPPublicKeyRingCollection(
                    PGPUtil.getDecoderStream(new ByteArrayInputStream(armoredKey)),
                    new JcaKeyFingerprintCalculator());
            PGPPublicKey signer = null;
            java.util.Iterator<PGPPublicKeyRing> iterator = rings.getKeyRings();
            while (iterator.hasNext()) {
                PGPPublicKeyRing ring = iterator.next();
                if (CLAUDE_SIGNING_FINGERPRINT.equals(hex(ring.getPublicKey().getFingerprint()))) {
                    signer = ring.getPublicKey(signature.getKeyID());
                    break;
                }
            }
            if (signer == null) throw new IOException("Claude manifest signing key fingerprint did not match Anthropic");
            signature.init(new JcaPGPContentVerifierBuilderProvider().setProvider(provider), signer);
            signature.update(manifest);
            if (!signature.verify()) throw new IOException("Claude manifest OpenPGP signature was invalid");
        } catch (PGPException e) {
            throw new IOException("Could not verify Claude manifest signature", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        return result.toString();
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(downloadTimeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "SpigotLLM/1.0");
        return connection;
    }

    private void extractTarGzip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        InputStream file = Files.newInputStream(archive);
        TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(file)));
        try {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Archive entry escaped destination: " + entry.getName());
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new IOException("Archive links are not accepted: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!entry.isFile()) continue;
                Files.createDirectories(target.getParent());
                OutputStream output = Files.newOutputStream(target);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = tar.read(buffer)) != -1) output.write(buffer, 0, read);
                } finally {
                    output.close();
                }
                if ((entry.getMode() & 0111) != 0) makeExecutable(target);
            }
        } finally {
            tar.close();
        }
    }

    private void verifySha256(Path path, String expected) throws IOException {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("Provider returned an invalid SHA-256 digest");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream input = Files.newInputStream(path);
            byte[] buffer = new byte[64 * 1024];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            } finally {
                input.close();
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            if (!actual.toString().equalsIgnoreCase(expected)) {
                throw new IOException("Downloaded runtime checksum did not match provider metadata");
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private void smokeTest(Path executable, String expectedVersion) throws IOException {
        Process process = new ProcessBuilder(executable.toAbsolutePath().toString(), "--version")
                .redirectErrorStream(true).start();
        String line = null;
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroy();
                throw new IOException("Runtime version check timed out");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try { line = reader.readLine(); } finally { reader.close(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("Runtime version check interrupted", e);
        }
        if (process.exitValue() != 0 || line == null || !line.contains(expectedVersion)) {
            throw new IOException("Runtime version check failed; expected " + expectedVersion + ", got " + line);
        }
    }

    private void makeExecutable(Path path) {
        path.toFile().setExecutable(true, true);
    }

    private String string(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) throw new IOException("Provider metadata omitted " + field);
        return value.getAsString();
    }

    private void validateVersion(String version) throws IOException {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9.]+)?")) {
            throw new IOException("Provider returned an invalid release version");
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        List<Path> paths = new ArrayList<Path>();
        Stream<Path> walk = Files.walk(root);
        try {
            java.util.Iterator<Path> iterator = walk.iterator();
            while (iterator.hasNext()) paths.add(iterator.next());
        } finally {
            walk.close();
        }
        Collections.sort(paths, Collections.reverseOrder());
        IOException failure = null;
        for (Path path : paths) {
            try { Files.deleteIfExists(path); } catch (IOException e) { failure = e; }
        }
        if (failure != null) throw failure;
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static final class Release {
        private final String version;
        private final String url;
        private final String sha256;

        private Release(String version, String url, String sha256) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
        }
    }
}
