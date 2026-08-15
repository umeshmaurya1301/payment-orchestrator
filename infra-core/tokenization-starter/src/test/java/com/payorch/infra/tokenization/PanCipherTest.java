package com.payorch.infra.tokenization;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PanCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final PanCipher cipher = new PanCipher(KEY);

    @Test
    void roundTrips() {
        String pan = "4242424242424242";

        PanCipher.Encrypted encrypted = cipher.encrypt(pan, "tok_abc");

        assertThat(cipher.decrypt(encrypted, "tok_abc")).isEqualTo(pan);
    }

    @Test
    void ciphertextDoesNotContainThePan() {
        PanCipher.Encrypted encrypted = cipher.encrypt("4242424242424242", "tok_abc");

        assertThat(new String(encrypted.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("4242");
    }

    /**
     * Two encryptions of the same card must not produce the same bytes. If they
     * did, the vault would leak which rows hold the same card - and with GCM a
     * repeated nonce is far worse than that.
     */
    @Test
    void usesAFreshNoncePerEncryption() {
        PanCipher.Encrypted first = cipher.encrypt("4242424242424242", "tok_a");
        PanCipher.Encrypted second = cipher.encrypt("4242424242424242", "tok_b");

        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    /**
     * The AAD binding. Someone with write access to the vault must not be able
     * to move one row's ciphertext under another row's token and have it
     * decrypt into a charge on the wrong card.
     */
    @Test
    void refusesToDecryptUnderADifferentToken() {
        PanCipher.Encrypted encrypted = cipher.encrypt("4242424242424242", "tok_original");

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "tok_other"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("card decryption failed");
    }

    @Test
    void refusesToDecryptTamperedCiphertext() {
        PanCipher.Encrypted encrypted = cipher.encrypt("4242424242424242", "tok_abc");
        encrypted.ciphertext()[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "tok_abc"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new PanCipher(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsAKeyThatIsNotBase64() {
        assertThatThrownBy(() -> new PanCipher("not base64 !!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    /** Failure messages must never echo the input they failed on. */
    @Test
    void failureMessagesCarryNoCardData() {
        PanCipher.Encrypted encrypted = cipher.encrypt("4242424242424242", "tok_abc");

        assertThatThrownBy(() -> cipher.decrypt(encrypted, "tok_other"))
                .hasMessageNotContaining("4242");
    }
}
