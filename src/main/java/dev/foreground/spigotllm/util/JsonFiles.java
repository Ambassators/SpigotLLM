package dev.foreground.spigotllm.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonFiles() {
    }

    public static <T> T read(Path path, Type type, T fallback) throws IOException {
        if (!Files.isRegularFile(path)) {
            return fallback;
        }
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        try {
            T value = GSON.fromJson(reader, type);
            return value == null ? fallback : value;
        } finally {
            reader.close();
        }
    }

    public static void writeAtomic(Path path, Object value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8);
            try {
                GSON.toJson(value, writer);
                writer.newLine();
            } finally {
                writer.close();
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }
}
