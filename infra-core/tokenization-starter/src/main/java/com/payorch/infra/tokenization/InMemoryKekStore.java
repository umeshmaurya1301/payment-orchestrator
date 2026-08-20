package com.payorch.infra.tokenization;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link KekStore} that holds keys in this process. Phase 9c.
 *
 * <h2>What this is honestly for</h2>
 *
 * <p>Local development and tests. It satisfies the interface's requirement -
 * keys are not in the ciphertext's backup domain, because they are not persisted
 * at all - by satisfying it in the least useful possible way: <strong>every key
 * is destroyed on restart</strong>, so every card tokenized under a
 * merchant-scoped key becomes unreadable when the process stops.
 *
 * <p>That makes it correct for demonstrating erasure and useless for holding
 * anything. Stated here rather than discovered: this is not the production
 * implementation, and the production implementation is Vault, which is
 * outstanding along with 9b's container.
 *
 * <p>The shared scope is the exception. It is seeded from configuration, so it
 * survives restarts and behaves exactly as the phase-9b key ring did - which is
 * what lets a deployment adopt scoping gradually rather than losing its vault on
 * the next deploy.
 */
public class InMemoryKekStore implements KekStore {

    private final Map<String, Map<String, byte[]>> byScope = new ConcurrentHashMap<>();
    private final Map<String, String> currentByScope = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> versionCounters = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * @param sharedKeys the {@link KeyRing#SHARED_SCOPE} versions from
     *                   configuration, {@code version -> base64 key}. These are
     *                   the only keys that survive a restart
     * @param sharedCurrent which shared version new shared-scope records use
     */
    public InMemoryKekStore(Map<String, String> sharedKeys, String sharedCurrent) {
        if (sharedKeys != null && !sharedKeys.isEmpty()) {
            Map<String, byte[]> parsed = new LinkedHashMap<>();
            sharedKeys.forEach((version, base64) -> parsed.put(version, decode(version, base64)));
            byScope.put(KeyRing.SHARED_SCOPE, new ConcurrentHashMap<>(parsed));
            if (sharedCurrent != null && !sharedCurrent.isBlank()) {
                if (!parsed.containsKey(sharedCurrent)) {
                    throw new IllegalStateException(
                            "the current shared KEK version '" + sharedCurrent
                                    + "' is not configured; available: " + parsed.keySet());
                }
                currentByScope.put(KeyRing.SHARED_SCOPE, sharedCurrent);
            }
        }
    }

    private static byte[] decode(String version, String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KEK version '" + version + "' is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("KEK version '" + version
                    + "' must decode to 32 bytes for AES-256, got " + raw.length);
        }
        return raw;
    }

    @Override
    public Optional<byte[]> find(String scope, String version) {
        Map<String, byte[]> versions = byScope.get(scope);
        if (versions == null) {
            return Optional.empty();
        }
        // Cloned, so a caller that wipes its copy - which EnvelopeCipher does -
        // cannot blank the stored key and silently shred a scope.
        return Optional.ofNullable(versions.get(version)).map(byte[]::clone);
    }

    @Override
    public String createCurrentVersion(String scope) {
        int next = versionCounters.computeIfAbsent(scope, s -> new AtomicInteger()).incrementAndGet();
        String version = "v" + next;

        byte[] key = new byte[32];
        random.nextBytes(key);
        byScope.computeIfAbsent(scope, s -> new ConcurrentHashMap<>()).put(version, key);
        currentByScope.put(scope, version);
        return version;
    }

    @Override
    public Optional<String> currentVersion(String scope) {
        return Optional.ofNullable(currentByScope.get(scope));
    }

    @Override
    public int forget(String scope) {
        Map<String, byte[]> versions = byScope.remove(scope);
        currentByScope.remove(scope);
        versionCounters.remove(scope);

        if (versions == null) {
            return 0;
        }
        // Wiped rather than merely dereferenced. Dropping the reference leaves
        // the bytes in the heap until a collection that may not come before a
        // core dump does - and "the key is gone" is the single claim this class
        // exists to make.
        versions.values().forEach(key -> Arrays.fill(key, (byte) 0));
        return versions.size();
    }

    @Override
    public Set<String> scopes() {
        return Set.copyOf(byScope.keySet());
    }
}
