package dev.foreground.spigotllm.session;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;
import dev.foreground.spigotllm.model.ReasoningEffort;
import dev.foreground.spigotllm.model.SessionMode;
import dev.foreground.spigotllm.util.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class SessionStore {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Path identitiesRoot;
    private final Map<String, SessionDocument> documents = new LinkedHashMap<String, SessionDocument>();

    public SessionStore(Path identitiesRoot) {
        this.identitiesRoot = identitiesRoot;
    }

    public static boolean validName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    public synchronized SessionRecord getOrCreateActive(Identity identity, Provider provider, SessionMode mode)
            throws IOException {
        SessionDocument document = document(identity);
        String activeKey = activeKey(provider, mode);
        String name = document.active.get(activeKey);
        SessionRecord existing = name == null ? null : find(document, provider, mode, name);
        if (existing != null) {
            return existing;
        }
        String defaultName = mode == SessionMode.AGENT ? "default-agent" : "default-chat";
        existing = find(document, provider, mode, defaultName);
        if (existing == null) {
            existing = new SessionRecord(defaultName, provider, mode);
            document.sessions.add(existing);
        }
        document.active.put(activeKey, existing.getName());
        save(identity, document);
        return existing;
    }

    public synchronized SessionRecord create(Identity identity, Provider provider, SessionMode mode, String name)
            throws IOException {
        if (!validName(name)) {
            throw new IllegalArgumentException("Session names must contain 1-32 letters, numbers, underscores, or dashes.");
        }
        SessionDocument document = document(identity);
        if (find(document, provider, mode, name) != null) {
            throw new IllegalArgumentException("That session already exists.");
        }
        SessionRecord record = new SessionRecord(name, provider, mode);
        document.sessions.add(record);
        document.active.put(activeKey(provider, mode), name);
        save(identity, document);
        return record;
    }

    public synchronized boolean use(Identity identity, Provider provider, SessionMode mode, String name)
            throws IOException {
        SessionDocument document = document(identity);
        SessionRecord record = find(document, provider, mode, name);
        if (record == null) {
            return false;
        }
        document.active.put(activeKey(provider, mode), record.getName());
        save(identity, document);
        return true;
    }

    public synchronized boolean rename(Identity identity, Provider provider, SessionMode mode,
                                       String oldName, String newName) throws IOException {
        if (!validName(newName)) {
            throw new IllegalArgumentException("Session names must contain 1-32 letters, numbers, underscores, or dashes.");
        }
        SessionDocument document = document(identity);
        SessionRecord record = find(document, provider, mode, oldName);
        if (record == null || find(document, provider, mode, newName) != null) {
            return false;
        }
        record.setName(newName);
        record.touch();
        String activeKey = activeKey(provider, mode);
        if (oldName.equalsIgnoreCase(document.active.get(activeKey))) {
            document.active.put(activeKey, newName);
        }
        save(identity, document);
        return true;
    }

    public synchronized SessionRecord delete(Identity identity, Provider provider, SessionMode mode, String name)
            throws IOException {
        SessionDocument document = document(identity);
        SessionRecord record = find(document, provider, mode, name);
        if (record == null) {
            return null;
        }
        document.sessions.remove(record);
        String activeKey = activeKey(provider, mode);
        if (name.equalsIgnoreCase(document.active.get(activeKey))) {
            document.active.remove(activeKey);
        }
        save(identity, document);
        return record;
    }

    public synchronized List<SessionRecord> list(Identity identity) throws IOException {
        List<SessionRecord> result = new ArrayList<SessionRecord>(document(identity).sessions);
        Collections.sort(result, new Comparator<SessionRecord>() {
            @Override
            public int compare(SessionRecord left, SessionRecord right) {
                int provider = left.getProvider().id().compareTo(right.getProvider().id());
                if (provider != 0) return provider;
                int mode = left.getMode().id().compareTo(right.getMode().id());
                if (mode != 0) return mode;
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return result;
    }

    public synchronized boolean isActive(Identity identity, SessionRecord record) throws IOException {
        String selected = document(identity).active.get(activeKey(record.getProvider(), record.getMode()));
        return selected != null && selected.equalsIgnoreCase(record.getName());
    }

    public synchronized void updateProviderId(Identity identity, SessionRecord record, String providerSessionId)
            throws IOException {
        record.setProviderSessionId(providerSessionId);
        record.touch();
        save(identity, document(identity));
    }

    public synchronized void updateReasoningEffort(Identity identity, SessionRecord record,
                                                   ReasoningEffort effort) throws IOException {
        if (effort == null || !effort.supports(record.getProvider())) {
            throw new IllegalArgumentException("Unsupported effort for " + record.getProvider().id() + ".");
        }
        record.setReasoningEffort(effort);
        record.touch();
        save(identity, document(identity));
    }

    public Path identityRoot(Identity identity) throws IOException {
        Path root = identitiesRoot.resolve(identity.key()).normalize();
        if (!root.startsWith(identitiesRoot.normalize())) {
            throw new IOException("Invalid identity path");
        }
        Files.createDirectories(root);
        return root;
    }

    public Path claudeSessionRoot(Identity identity, SessionRecord record) throws IOException {
        Path root = identityRoot(identity).resolve("claude-sessions").resolve(safeDirectory(record)).normalize();
        Files.createDirectories(root);
        return root;
    }

    private String safeDirectory(SessionRecord record) {
        return record.getMode().id() + "-" + record.getStorageId();
    }

    private SessionDocument document(Identity identity) throws IOException {
        SessionDocument cached = documents.get(identity.key());
        if (cached != null) {
            return cached;
        }
        Path file = identityRoot(identity).resolve("sessions.json");
        SessionDocument loaded = JsonFiles.read(file, SessionDocument.class, new SessionDocument());
        boolean migrated = false;
        for (SessionRecord record : loaded.sessions) migrated |= record.ensureStorageId();
        if (migrated) JsonFiles.writeAtomic(file, loaded);
        documents.put(identity.key(), loaded);
        return loaded;
    }

    private void save(Identity identity, SessionDocument document) throws IOException {
        JsonFiles.writeAtomic(identityRoot(identity).resolve("sessions.json"), document);
    }

    private SessionRecord find(SessionDocument document, Provider provider, SessionMode mode, String name) {
        for (SessionRecord record : document.sessions) {
            if (record.getProvider() == provider && record.getMode() == mode
                    && record.getName().equalsIgnoreCase(name)) {
                return record;
            }
        }
        return null;
    }

    private String activeKey(Provider provider, SessionMode mode) {
        return provider.id() + ":" + mode.id();
    }
}
