package com.payorch.infra.tokenization;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM over the card number, as it sits at rest in the vault.
 *
 * <p>Three choices worth defending:
 *
 * <p><strong>GCM, not CBC.</strong> GCM is authenticated: a modified ciphertext
 * fails to decrypt rather than producing plausible-looking garbage. For a value
 * that is about to be sent to a payment provider, silently decrypting to the
 * wrong digits is a worse outcome than an outage.
 *
 * <p><strong>A fresh 12-byte IV per row.</strong> GCM's security collapses
 * completely if a nonce is ever reused with the same key - not degraded,
 * collapsed, to the point of key-stream recovery. 12 bytes is the size the mode
 * is specified for; anything else forces an extra hashing step inside the
 * cipher.
 *
 * <p><strong>The token is the additional authenticated data.</strong> That binds
 * the ciphertext to its row. Someone with write access to the vault cannot move
 * one card's ciphertext under another card's token and have it decrypt - the tag
 * check fails, so a row-swapping attack turns into an error rather than a
 * charge on the wrong card.
 *
 * <p>The key here is a single static secret held in configuration. That is the
 * phase-1 form; phase 9c replaces it with envelope encryption and a rotating
 * data-encryption key, which is why {@link Encrypted} carries no key identifier
 * yet - adding one is that phase's first migration.
 */
public final class PanCipher {

    /** GCM standard nonce length, in bytes. */
    private static final int IV_LENGTH = 12;

    /** GCM authentication tag length, in bits. The maximum the mode defines. */
    private static final int TAG_LENGTH_BITS = 128;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key 32 raw bytes, base64-encoded. AES-256 rather than
     *        AES-128 because the cost difference is negligible and the key
     *        outlives the code that chose it.
     */
    public PanCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("payorch.vault.key is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "payorch.vault.key must decode to 32 bytes for AES-256, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
    }

    /** An encrypted card number and the nonce it was encrypted under. */
    public record Encrypted(byte[] iv, byte[] ciphertext) {
    }

    public Encrypted encrypt(String pan, String token) {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        byte[] plaintext = pan.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(token.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(iv, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            // No detail from the exception is propagated: JCE messages can echo
            // input length and, in some providers, input itself.
            throw new IllegalStateException("card encryption failed");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public String decrypt(Encrypted encrypted, String token) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, encrypted.iv()));
            cipher.updateAAD(token.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted.ciphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("card decryption failed");
        }
    }
}
