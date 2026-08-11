package dev.foreground.spigotllm.runtime;

import dev.foreground.spigotllm.model.Provider;

import java.util.Locale;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class Platform {
    private final String os;
    private final String arch;
    private final boolean musl;

    private Platform(String os, String arch, boolean musl) {
        this.os = os;
        this.arch = arch;
        this.musl = musl;
    }

    static Platform detect() throws IllegalStateException {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String os;
        if (osName.contains("win")) os = "windows";
        else if (osName.contains("mac") || osName.contains("darwin")) os = "darwin";
        else if (osName.contains("linux")) os = "linux";
        else throw new IllegalStateException("Unsupported operating system: " + osName);

        String arch;
        if (archName.equals("x86_64") || archName.equals("amd64")) arch = "x64";
        else if (archName.equals("aarch64") || archName.equals("arm64")) arch = "arm64";
        else throw new IllegalStateException("Unsupported architecture: " + archName);
        return new Platform(os, arch, os.equals("linux") && detectMusl());
    }

    String assetName(Provider provider) {
        if (provider == Provider.CLAUDE) {
            return os + "-" + arch + (os.equals("linux") && musl ? "-musl" : "");
        }
        String cpu = arch.equals("arm64") ? "aarch64" : "x86_64";
        if (os.equals("darwin")) return "codex-package-" + cpu + "-apple-darwin.tar.gz";
        if (os.equals("windows")) return "codex-package-" + cpu + "-pc-windows-msvc.tar.gz";
        return "codex-package-" + cpu + "-unknown-linux-musl.tar.gz";
    }

    String executableName(Provider provider) {
        return provider.id() + (os.equals("windows") ? ".exe" : "");
    }

    boolean windows() {
        return os.equals("windows");
    }

    private static boolean detectMusl() {
        if (Files.isRegularFile(Paths.get("/etc/alpine-release"))) return true;
        return hasMuslLoader(Paths.get("/lib")) || hasMuslLoader(Paths.get("/usr/lib"));
    }

    private static boolean hasMuslLoader(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try {
            DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "ld-musl-*.so.1");
            try { return stream.iterator().hasNext(); } finally { stream.close(); }
        } catch (Exception ignored) {
            return false;
        }
    }
}
