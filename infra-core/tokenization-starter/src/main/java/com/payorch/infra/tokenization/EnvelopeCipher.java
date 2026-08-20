package com.payorch.infra.tokenization;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Envelope encryption over the card number. Phase 9b.
 *
 * <h2>The one thing this buys, and it is not secrecy</h2>
 *
 * <p>{@link PanCipher} already encrypts the PAN correctly - AES-256-GCM, fresh
 * nonce per row, the token as AAD. Nothing here makes the ciphertext harder to
 * break. What it makes possible is <strong>rotating the key without rewriting
 * the data</strong>, and that difference is structural rather than
 * cryptographic.
 *
 * <p>With one key applied directly, rotation means decrypting and re-encrypting
 * every row. On a vault of any size that is a long, stateful, resumable job that
 * touches the most sensitive table in the system, holds plaintext PANs in memory
 * while it runs, and cannot be safely interrupted. Which is why, in practice,
 * nobody rotates: the operation is so unpleasant that the key stays as it is
 * until an incident forces it.
 *
 * <p>Envelope encryption replaces that with a small one. Each record gets its
 * own <strong>DEK</strong>, and the PAN is encrypted under it. The DEK is then
 * encrypted - "wrapped" - under the <strong>KEK</strong>, and only the wrapped
 * DEK is stored. Rotating the KEK means unwrapping and re-wrapping a 32-byte
 * value per row. <strong>No PAN is decrypted, and no PAN ciphertext is
 * touched.</strong> {@code EnvelopeCipherTest} asserts the ciphertext bytes are
 * byte-identical across a rotation, because that claim is the whole point and is
 * easy to believe without checking.
 *
 * <h2>Per-record DEKs, not one DEK for the table</h2>
 *
 * <p>A single DEK wrapped under a KEK would also make rotation cheap, and it
 * would give back what the scheme just bought: one compromised DEK exposes every
 * card. Per-record keys mean the blast radius of a leaked DEK is one card, and
 * they are what makes phase 9c's crypto-shredding work at all - destroying one
 * record's key destroys one record's data, which is exactly what a
 * right-to-erasure request asks for.
 *
 * <p>The cost is 44 bytes per row. That is the entire trade.
 *
 * <h2>The token is still the AAD</h2>
 *
 * <p>Carried over from phase 1 and worth restating: binding the ciphertext to
 * its token means someone with write access cannot move one card's ciphertext
 * under another card's token and have it decrypt. A row-swapping attack becomes
 * a tag failure rather than a charge on the wrong card.
 *
 * <p>Note what is <em>not</em> bound: the wrapped DEK is bound to its own domain
 * string rather than to the token - see {@link KeyRing}. Binding it to the token
 * as well would be free and would make re-wrapping require the token, which is
 * fine, but it would also mean a rotation job needed to read a column it has no
 * business reading. The DEK is protected by the KEK; the row binding is the
 * PAN's job.
 */
public final class EnvelopeCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final KeyRing keys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeCipher(KeyRing keys) {
        this.keys = keys;
    }

    /**
     * One encrypted card, and everything needed to read it back.
     *
     * @param iv         the nonce the PAN was encrypted under
     * @param ciphertext the encrypted PAN. <strong>Never rewritten</strong>,
     *                   including by a KEK rotation
     * @param wrappedKey the record's DEK, encrypted under a KEK, naming the KEK
     *                   version that did it. The only field rotation changes
     */
    public record Sealed(byte[] iv, byte[] ciphertext, KeyRing.WrappedKey wrappedKey) {
    }

    /**
     * @param keyScope the erasure boundary this record belongs to - the
     *        merchant. Phase 9c. Choosing it wrongly is not something a later
     *        refactor fixes: records wrapped under a shared key cannot be
     *        separated afterwards without decrypting and re-encrypting them,
     *        which is the expensive migration all of 9b was arranged to avoid
     */
    public Sealed encrypt(String pan, String token, String keyScope) {
        byte[] dek = keys.newDataKey();
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        byte[] plaintext = pan.getBytes(StandardCharsets.UTF_8);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(token.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);

            return new Sealed(iv, ciphertext, keys.wrap(keyScope, dek));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("card encryption failed");
        } finally {
            // Both wiped, and the DEK matters more than the plaintext. A DEK
            // left in a live array is a key to one card sitting in a heap that
            // may be dumped, swapped or core-dumped - and unlike the plaintext
            // it is not obviously card data to anything scanning for it.
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(dek, (byte) 0);
        }
    }

    public String decrypt(Sealed sealed, String token) {
        byte[] dek = keys.unwrap(sealed.wrappedKey());
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, sealed.iv()));
            cipher.updateAAD(token.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("card decryption failed");
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * Moves a record's DEK to the current KEK. THE POINT OF THE WHOLE SCHEME.
     *
     * <p>Takes the wrapped key and returns a wrapped key. It does not take the
     * ciphertext, cannot see the PAN, and has no parameter through which a PAN
     * could reach it - so "rotation never decrypts a card" is enforced by the
     * signature rather than promised by a comment. A rotation job written
     * against this method cannot get it wrong.
     *
     * <p>The DEK itself is unchanged. Only its wrapping moves.
     *
     * @return the same DEK, wrapped under the current KEK version
     */
    public KeyRing.WrappedKey rewrap(KeyRing.WrappedKey wrapped) {
        byte[] dek = keys.unwrap(wrapped);
        try {
            // Re-wrapped within its OWN scope. A rotation must never move a
            // record between erasure boundaries: doing so would put a merchant's
            // card under a scope their erasure request will not reach, and the
            // deletion would silently fail to delete.
            return keys.wrap(wrapped.keyScope(), dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Whether this record already uses the current KEK, and so needs no re-wrap. */
    public boolean isCurrent(KeyRing.WrappedKey wrapped) {
        return keys.isCurrent(wrapped);
    }

    /** The version new records in this scope are wrapped under, if any yet. */
    public java.util.Optional<String> currentKeyVersion(String keyScope) {
        return keys.currentVersion(keyScope);
    }

    /** Adds a new current version to a scope. The first half of a rotation. */
    public String rotate(String keyScope) {
        return keys.rotate(keyScope);
    }

    /**
     * Erases a scope: every record wrapped under it becomes permanently
     * unreadable, in every copy that exists anywhere.
     *
     * @return how many key versions were destroyed
     */
    public int forget(String keyScope) {
        return keys.forget(keyScope);
    }
}
