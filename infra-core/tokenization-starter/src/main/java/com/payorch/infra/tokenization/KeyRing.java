package com.payorch.infra.tokenization;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wraps and unwraps data-encryption keys, under a KEK chosen by scope and
 * version. Phase 9b, scoped in 9c.
 *
 * <h2>Version: why a single key cannot be rotated</h2>
 *
 * <p>Not "is awkward to rotate" - cannot. The moment a new key exists, every
 * value encrypted under the old one is unreadable, so rotation and a full
 * rewrite of the data become the same operation. Several versions live at once;
 * exactly one is current per scope and used for new wrapping, and the rest are
 * kept only to unwrap what they wrapped. Every wrapped value names its version,
 * so decryption never guesses and rotation never has to be atomic.
 *
 * <h2>Scope: why a single key cannot be erased</h2>
 *
 * <p>The same argument one level up, and it is phase 9c's. If every merchant's
 * cards are wrapped under one KEK, there is no key whose destruction erases one
 * merchant - destroying it erases all of them. The unit the business must be
 * able to erase has to be the unit the key hierarchy names.
 *
 * <p>So a scope is an <strong>erasure boundary</strong>, and here it is the
 * merchant, because that is who a right-to-erasure request arrives for.
 * {@link #SHARED_SCOPE} is the default and is deliberately not erasable in
 * isolation - erasure should be a decision, and a scheme whose default is
 * "shreddable" invites somebody to shred more than they meant to.
 *
 * <h2>Where the keys are</h2>
 *
 * <p>Behind {@link KekStore}, whose contract carries the requirement that makes
 * any of this work: key material must not live in the same backup domain as the
 * ciphertext it protects. Deleting a row does not remove it from last night's
 * backup; destroying the key makes every copy of the ciphertext permanently
 * undecryptable without anybody having to find those copies.
 *
 * <p>The implementation shipped here holds keys in memory
 * ({@link InMemoryKekStore}), which is honest for development and useless for
 * anything else. Vault is outstanding.
 */
public final class KeyRing {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /**
     * The scope used unless one is chosen. Seeded from configuration and not
     * erasable in isolation.
     */
    public static final String SHARED_SCOPE = "shared";

    /**
     * The AAD every wrap is bound to.
     *
     * <p>Domain separation, plus the scope. A wrapped DEK and an encrypted PAN
     * are both AES-GCM ciphertexts under this scheme, and without a
     * distinguishing AAD there is nothing structural stopping one being fed to
     * the other's decryption path. Binding the scope in as well means a wrapped
     * DEK cannot be relabelled into another merchant's scope and still unwrap -
     * so a row edited to point at a scope that has not been erased does not
     * resurrect a card that was.
     */
    private static final String WRAP_AAD_PREFIX = "payorch:dek-wrap:";

    private final KekStore keys;
    private final SecureRandom random = new SecureRandom();

    public KeyRing(KekStore keys) {
        this.keys = keys;
    }

    /**
     * A data-encryption key, wrapped, naming the scope and version of the KEK
     * that wrapped it.
     *
     * <p>Both are needed to unwrap and both are stored beside the ciphertext.
     * Neither is secret: knowing that a record belongs to a merchant and was
     * wrapped under that merchant's third key reveals nothing without the key.
     */
    public record WrappedKey(String keyScope, String kekVersion, byte[] iv, byte[] ciphertext) {
    }

    /** Fresh 32 bytes for one record. Never reused, never stored unwrapped. */
    public byte[] newDataKey() {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        return dek;
    }

    /**
     * Wraps a DEK under the current KEK for a scope, creating that scope's first
     * key if it has none.
     *
     * <p>Created on first use rather than at merchant onboarding, deliberately: a
     * merchant who has never taken a payment has nothing to protect and nothing
     * to erase, and provisioning keys for them is key material that exists for
     * no reason.
     *
     * <p>There is no overload taking a version. Wrapping under an old KEK would
     * be a way to quietly undo a rotation one record at a time, and no
     * legitimate caller wants it.
     */
    public WrappedKey wrap(String scope, byte[] dataKey) {
        String version = keys.currentVersion(scope)
                .orElseGet(() -> keys.createCurrentVersion(scope));
        byte[] kek = keys.find(scope, version)
                .orElseThrow(() -> new UnknownKeyException(scope, version));

        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(scope));
            return new WrappedKey(scope, version, iv, cipher.doFinal(dataKey));
        } catch (GeneralSecurityException e) {
            // No detail propagated: JCE messages can echo input length and, in
            // some providers, the input itself.
            throw new IllegalStateException("key wrapping failed");
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /**
     * Unwraps a DEK under whichever KEK wrapped it.
     *
     * <p>This is what makes rotation cheap AND what makes erasure absolute. A
     * record wrapped under {@code v1} stays readable for as long as {@code v1}
     * exists, whether or not it has been re-wrapped yet - so rotation has no
     * cutover. And the instant a scope's keys are destroyed, every record naming
     * that scope is unreadable everywhere, including in copies nobody can reach.
     *
     * @throws UnknownKeyException if the key is gone. Its own type, because it
     *         is operationally different from corruption: the data is intact and
     *         a key is missing. In 9b that is a mistake to recover from by
     *         restoring the key; in 9c it is the successful outcome of an
     *         erasure request, and the two are indistinguishable from here on
     *         purpose - a system that could tell them apart could tell you what
     *         it had erased
     */
    public byte[] unwrap(WrappedKey wrapped) {
        byte[] kek = keys.find(wrapped.keyScope(), wrapped.kekVersion())
                .orElseThrow(() -> new UnknownKeyException(
                        wrapped.keyScope(), wrapped.kekVersion()));
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, wrapped.iv()));
            cipher.updateAAD(aad(wrapped.keyScope()));
            return cipher.doFinal(wrapped.ciphertext());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key unwrapping failed");
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    /** Whether this wrapped key already uses its scope's current KEK. */
    public boolean isCurrent(WrappedKey wrapped) {
        return keys.currentVersion(wrapped.keyScope())
                .map(current -> current.equals(wrapped.kekVersion()))
                .orElse(false);
    }

    /** The version new records in this scope are wrapped under, if it has one yet. */
    public java.util.Optional<String> currentVersion(String scope) {
        return keys.currentVersion(scope);
    }

    /** Adds a new current version to a scope. The first half of a rotation. */
    public String rotate(String scope) {
        return keys.createCurrentVersion(scope);
    }

    /**
     * <strong>Erases a scope.</strong> See {@link KekStore#forget}.
     *
     * @return how many key versions were destroyed
     */
    public int forget(String scope) {
        return keys.forget(scope);
    }

    /** Scopes that still hold key material. */
    public Set<String> scopes() {
        return keys.scopes();
    }

    private static byte[] aad(String scope) {
        return (WRAP_AAD_PREFIX + scope).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The KEK that wrapped this value no longer exists.
     *
     * <p>The message names the scope and version and nothing else - never the
     * ciphertext, and never whether the key was erased or simply never created.
     */
    public static class UnknownKeyException extends RuntimeException {

        public UnknownKeyException(String scope, String version) {
            super("no key material for scope '" + scope + "' version '" + version
                    + "' - it was never created, or it has been erased");
        }
    }
}
