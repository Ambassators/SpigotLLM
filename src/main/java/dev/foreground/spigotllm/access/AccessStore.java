package dev.foreground.spigotllm.access;

import com.google.gson.reflect.TypeToken;
import dev.foreground.spigotllm.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AccessStore {
    private final Path file;
    private final Map<UUID, AuthorizedOperator> authorized = new LinkedHashMap<UUID, AuthorizedOperator>();

    public AccessStore(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        List<AuthorizedOperator> entries = JsonFiles.read(
                file,
                new TypeToken<List<AuthorizedOperator>>() { }.getType(),
                new ArrayList<AuthorizedOperator>());
        authorized.clear();
        for (AuthorizedOperator entry : entries) {
            try {
                UUID uuid = UUID.fromString(entry.getUuid());
                authorized.put(uuid, entry);
            } catch (RuntimeException ignored) {
                // Ignore malformed rows rather than weakening the allowlist.
            }
        }
    }

    public synchronized boolean isAuthorized(UUID uuid) {
        return authorized.containsKey(uuid);
    }

    public synchronized boolean add(UUID uuid, String lastKnownName) throws IOException {
        boolean isNew = !authorized.containsKey(uuid);
        authorized.put(uuid, new AuthorizedOperator(uuid.toString(), lastKnownName, System.currentTimeMillis()));
        save();
        return isNew;
    }

    public synchronized boolean remove(UUID uuid) throws IOException {
        if (authorized.remove(uuid) == null) {
            return false;
        }
        save();
        return true;
    }

    public synchronized List<AuthorizedOperator> list() {
        List<AuthorizedOperator> result = new ArrayList<AuthorizedOperator>(authorized.values());
        Collections.sort(result, new Comparator<AuthorizedOperator>() {
            @Override
            public int compare(AuthorizedOperator left, AuthorizedOperator right) {
                String a = left.getLastKnownName() == null ? left.getUuid() : left.getLastKnownName();
                String b = right.getLastKnownName() == null ? right.getUuid() : right.getLastKnownName();
                return a.compareToIgnoreCase(b);
            }
        });
        return result;
    }

    private void save() throws IOException {
        JsonFiles.writeAtomic(file, new ArrayList<AuthorizedOperator>(authorized.values()));
    }
}
