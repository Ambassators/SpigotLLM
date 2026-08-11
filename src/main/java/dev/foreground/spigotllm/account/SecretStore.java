package dev.foreground.spigotllm.account;

import dev.foreground.spigotllm.model.Identity;
import dev.foreground.spigotllm.model.Provider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public final class SecretStore {
    private static final byte FORMAT_VERSION = 1;
    private static final int KEY_BYTES = 16;
    private static final int NONCE_BYTES = 12;

    private final Path identitiesRoot;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    private volatile byte[] masterKey;

    public SecretStore(Path dataFolder) {
        this.identitiesRoot = dataFolder.resolve("identities");
        this.keyFile = dataFolder.resolve("secrets.key");
    }

    public synchronized void initialize() throws IOException {
        Files.createDirectories(identitiesRoot);
        if (Files.isRegularFile(keyFile)) {
            byte[] decoded = Base64.getDecoder().decode(new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII).trim());
            if (decoded.length != KEY_BYTES) {
                throw new IOException("secrets.key has an invalid length");
            }
            masterKey = decoded;
            return;
        }
        byte[] generated = new byte[KEY_BYTES];
        random.nextBytes(generated);
        Files.write(keyFile, Base64.getEncoder().encode(generated));
        setOwnerOnly(keyFile);
        masterKey = generated;
    }

    public void put(Identity identity, Provider provider, String secret) throws IOException {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Secret cannot be empty");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(identity, provider));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[1 + nonce.length + encrypted.length];
            output[0] = FORMAT_VERSION;
            System.arraycopy(nonce, 0, output, 1, nonce.length);
            System.arraycopy(encrypted, 0, output, 1 + nonce.length, encrypted.length);
            Path path = secretPath(identity, provider);
            Files.createDirectories(path.getParent());
            Files.write(path, Base64.getEncoder().encode(output));
            setOwnerOnly(path);
            Arrays.fill(encrypted, (byte) 0);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not encrypt provider credential", e);
        }
    }

    public String get(Identity identity, Provider provider) throws IOException {
        Path path = secretPath(identity, provider);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        byte[] input = Base64.getDecoder().decode(Files.readAllBytes(path));
        if (input.length <= 1 + NONCE_BYTES || input[0] != FORMAT_VERSION) {
            throw new IOException("Credential file has an invalid format");
        }
        byte[] nonce = Arrays.copyOfRange(input, 1, 1 + NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(input, 1 + NONCE_BYTES, input.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(identity, provider));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not decrypt provider credential", e);
        } finally {
            Arrays.fill(input, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    public boolean has(Identity identity, Provider provider) throws IOException {
        return Files.isRegularFile(secretPath(identity, provider));
    }

    public void remove(Identity identity, Provider provider) throws IOException {
        Files.deleteIfExists(secretPath(identity, provider));
    }

    private byte[] key() throws IOException {
        byte[] value = masterKey;
        if (value == null) {
            throw new IOException("SecretStore has not been initialized");
        }
        return value;
    }

    private Path secretPath(Identity identity, Provider provider) throws IOException {
        Path path = identitiesRoot.resolve(identity.key()).resolve("credentials")
                .resolve(provider.id() + ".secret").normalize();
        if (!path.startsWith(identitiesRoot.normalize())) {
            throw new IOException("Invalid credential path");
        }
        return path;
    }

    private byte[] aad(Identity identity, Provider provider) {
        return (identity.key() + ":" + provider.id()).getBytes(StandardCharsets.UTF_8);
    }

    private void setOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows inherits ACLs from the plugin data directory.
        } catch (IOException ignored) {
            // Best effort; failure is surfaced by normal file access if it matters.
        }
    }
}
