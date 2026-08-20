package com.payorch.infra.tokenization;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The key-encryption keys, by version. Phase 9b.
 *
 * <h2>What a key ring is for</h2>
 *
 * <p>A single key cannot be rotated. Not "is awkward to rotate" - cannot: the
 * moment a new key exists, every value encrypted under the old one is
 * unreadable, so rotation and a full rewrite of the data become the same
 * operation. That is why phase 1's {@link PanCipher} took one static key and why
 * it was always going to be replaced here.
 *
 * <p>A ring holds several versions at once. Exactly one is <em>current</em> and
 * used for new wrapping; the rest are retired and used only for unwrapping what
 * they wrapped. Every wrapped value carries the version that produced it, so
 * decryption never has to guess and rotation never has to be atomic.
 *
 * <p>This is the shape HashiCorp Vault's transit engine exposes, deliberately.
 * The interesting property is not where the bytes live - it is that the
 * ciphertext names its key version, and that is true whether the KEK sits in
 * Vault, in a KMS, or in this class.
 *
 * <h2>What this implementation is and is not</h2>
 *
 * <p><strong>The keys are in configuration.</strong> A real deployment holds the
 * KEK in Vault or a cloud KMS, where the unwrap operation happens inside the key
 * store and the KEK never reaches this process at all - which is the property
 * that makes a memory dump of this service uninteresting. That is 9b's Vault
 * container, and it needs one.
 *
 * <p>What is <em>not</em> deferred is the design: per-record DEKs, wrapped
 * values that name their key version, and rotation that never touches the
 * encrypted records. Those are the parts that are expensive to retrofit, and
 * they are real here. Swapping the wrap/unwrap implementation for a Vault call
 * is a change to two methods; changing a schema that assumed one static key is a
 * change to every row.
 *
 * <p>Said plainly because phase 9's own trap list asks for it: this is
 * <strong>local key material, not a hardware-backed or externally-held KEK</strong>.
 */
public final class KeyRing {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * The AAD every wrap is bound to.
     *
     * <p>Domain separation. A wrapped DEK and an encrypted PAN are both
     * AES-GCM ciphertexts under this scheme, and without a distinguishing AAD
     * there is nothing structural stopping one being fed to the other's
     * decryption path. Cheap, and it removes a class of confusion attack
     * entirely.
     */
    private static final byte[] WRAP_AAD = "payorch:dek-wrap".getBytes(StandardCharsets.UTF_8);

    private final Map<String, SecretKeySpec> byVersion;
    private final String currentVersion;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keysByVersion base64 32-byte keys, keyed by version label. Insertion
     *                      order is irrelevant; {@code currentVersion} decides
     *                      which one wraps
     * @param currentVersion the version new DEKs are wrapped under
     */
    public KeyRing(Map<String, String> keysByVersion, String currentVersion) {
        if (keysByVersion == null || keysByVersion.isEmpty()) {
            throw new IllegalStateException("a key ring needs at least one KEK version");
        }
        if (!keysByVersion.containsKey(currentVersion)) {
            // A ring whose current version is missing would encrypt nothing and
            // fail on the first payment. Failing here names the actual problem.
            throw new IllegalStateException(
                    "the current KEK version '" + currentVersion + "' is not on the key ring; "
                            + "available: " + keysByVersion.keySet());
        }

        Map<String, SecretKeySpec> parsed = new LinkedHashMap<>();
        keysByVersion.forEach((version, base64) -> parsed.put(version, parse(version, base64)));
        this.byVersion = Map.copyOf(parsed);
        this.currentVersion = currentVersion;
    }

    private static SecretKeySpec parse(String version, String base64Key) {
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
        SecretKeySpec key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        return key;
    }

    /** A data-encryption key, wrapped, and the version of the KEK that wrapped it. */
    public record WrappedKey(String kekVersion, byte[] iv, byte[] ciphertext) {
    }

    /** Fresh 32 bytes for one record. Never reused, never stored unwrapped. */
    public byte[] newDataKey() {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        return dek;
    }

    /**
     * Wraps a DEK under the <em>current</em> KEK.
     *
     * <p>Always the current one - there is no overload taking a version. Wrapping
     * under an old KEK would be a way to quietly undo a rotation one record at a
     * time, and no legitimate caller wants it.
     */
    public WrappedKey wrap(byte[] dataKey) {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, byVersion.get(currentVersion),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(WRAP_AAD);
            return new WrappedKey(currentVersion, iv, cipher.doFinal(dataKey));
        } catch (GeneralSecurityException e) {
            // No detail propagated: JCE messages can echo input length, and in
            // some providers the input itself.
            throw new IllegalStateException("key wrapping failed");
        }
    }

    /**
     * Unwraps a DEK under whichever KEK version wrapped it.
     *
     * <p>This is what makes rotation cheap: a record wrapped under {@code v1}
     * stays readable for as long as {@code v1} is on the ring, whether or not it
     * has been re-wrapped yet. Rotation therefore has no cutover - the ring is
     * updated, new writes use the new version, and the re-wrap job catches up in
     * its own time.
     *
     * @throws UnknownKeyVersionException if the version has been removed from
     *         the ring. Its own type, because it is operationally different from
     *         corruption: the data is fine and a key is missing, which is
     *         recoverable by restoring the key and is not recoverable by
     *         anything else. (It is also exactly what phase 9c's
     *         crypto-shredding produces on purpose.)
     */
    public byte[] unwrap(WrappedKey wrapped) {
        SecretKeySpec kek = byVersion.get(wrapped.kekVersion());
        if (kek == null) {
            throw new UnknownKeyVersionException(wrapped.kekVersion(), byVersion.keySet());
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, kek,
                    new GCMParameterSpec(TAG_LENGTH_BITS, wrapped.iv()));
            cipher.updateAAD(WRAP_AAD);
            return cipher.doFinal(wrapped.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key unwrapping failed");
        }
    }

    /** The version new records are wrapped under. */
    public String currentVersion() {
        return currentVersion;
    }

    /** Every version this ring can still unwrap. */
    public Set<String> versions() {
        return byVersion.keySet();
    }

    /** Whether this wrapped key already uses the current KEK. */
    public boolean isCurrent(WrappedKey wrapped) {
        return currentVersion.equals(wrapped.kekVersion());
    }

    /**
     * The KEK that wrapped this value is no longer on the ring.
     *
     * <p>The message names the version and the versions that ARE available, and
     * nothing else. It must never carry the ciphertext or any part of it.
     */
    public static class UnknownKeyVersionException extends RuntimeException {

        public UnknownKeyVersionException(String version, Set<String> available) {
            super("no KEK version '" + version + "' on the key ring; available: " + available);
        }
    }
}
