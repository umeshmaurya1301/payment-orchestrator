package com.payorch.infra.tokenization;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9b. Envelope encryption, and the rotation it exists to make possible.
 *
 * <p>The headline is {@link #rotatingTheKekDoesNotTouchASingleCardCiphertext()}.
 * Everything else here is table stakes that any AES-GCM wrapper should pass;
 * that one is the claim the scheme is bought for, it is easy to believe without
 * checking, and it is the reason the schema carries 44 extra bytes per row.
 */
class EnvelopeCipherTest {

    private static final String PAN = "4242424242424242";
    private static final String TOKEN = "tok_AAAAAAAAAAAAAAAAAAAAAA";

    private static final String KEK_V1 = key();
    private static final String KEK_V2 = key();

    /**
     * Every record here lives in the shared scope, so these tests are about the
     * cipher rather than about erasure. Scope-specific behaviour has its own
     * tests below and in {@link VaultRotationTest}.
     */
    private static final String SCOPE = KeyRing.SHARED_SCOPE;

    /** A ring holding only v1, with v1 current. The state before a rotation. */
    private final KeyRing beforeRotation = ring("v1", Map.of("v1", KEK_V1));

    /**
     * Both versions, v2 current. The state DURING and after a rotation - and
     * note that v1 stays on the ring, which is what lets records that have not
     * been re-wrapped yet still be read.
     */
    private final KeyRing afterRotation =
            ring("v2", ordered("v1", KEK_V1, "v2", KEK_V2));

    private static String key() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static Map<String, String> ordered(String k1, String v1, String k2, String v2) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private static KeyRing ring(String current, Map<String, String> keys) {
        return new KeyRing(new InMemoryKekStore(keys, current));
    }

    // --- the basics --------------------------------------------------------

    @Test
    void aCardRoundTrips() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);

        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);

        assertThat(cipher.decrypt(sealed, TOKEN)).isEqualTo(PAN);
    }

    @Test
    void theCiphertextDoesNotContainTheCard() {
        EnvelopeCipher.Sealed sealed = new EnvelopeCipher(beforeRotation).encrypt(PAN, TOKEN, SCOPE);

        assertThat(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(PAN)
                .doesNotContain("424242");
    }

    /**
     * The wrapped DEK must not be the DEK.
     *
     * <p>Phase 9's trap list names this: <em>"storing the DEK next to the
     * ciphertext unencrypted - easy to get right, easy to get subtly wrong"</em>.
     * The subtle wrong version is a refactor that stores the key it just
     * generated instead of the wrapped one, and every other test here would
     * still pass.
     */
    @Test
    void theStoredKeyIsWrappedAndNotTheKeyItself() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);

        // If the stored bytes WERE the DEK, decrypting with them directly would
        // work. It must not.
        byte[] stored = sealed.wrappedKey().ciphertext();
        assertThatThrownBy(() -> decryptWithRawKey(sealed, stored))
                .as("the stored key material must not be usable as a decryption key")
                .isInstanceOf(Exception.class);

        // And the real DEK must round-trip only via the KEK.
        byte[] unwrapped = beforeRotation.unwrap(sealed.wrappedKey());
        assertThat(unwrapped).hasSize(32).isNotEqualTo(stored);
    }

    /**
     * Every record gets its own key. A single table-wide DEK would also make
     * rotation cheap and would give back what the scheme buys: one compromised
     * key exposing every card.
     */
    @Test
    void everyRecordGetsItsOwnDataKey() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        Set<String> deks = new HashSet<>();

        for (int i = 0; i < 50; i++) {
            EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);
            deks.add(Base64.getEncoder()
                    .encodeToString(beforeRotation.unwrap(sealed.wrappedKey())));
        }

        assertThat(deks).hasSize(50);
    }

    /**
     * GCM's security does not degrade on nonce reuse, it collapses - to the
     * point of key-stream recovery. Two nonces are generated per encryption
     * here, one for the PAN and one for the wrap, and both have to be fresh.
     */
    @Test
    void noNonceIsEverReused() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        Set<String> panNonces = new HashSet<>();
        Set<String> wrapNonces = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);
            panNonces.add(Base64.getEncoder().encodeToString(sealed.iv()));
            wrapNonces.add(Base64.getEncoder().encodeToString(sealed.wrappedKey().iv()));
        }

        assertThat(panNonces).as("PAN nonces").hasSize(200);
        assertThat(wrapNonces).as("DEK-wrap nonces").hasSize(200);
    }

    /**
     * The token is the AAD, so a ciphertext cannot be moved to another row.
     * Carried over from phase 1 and re-asserted because the encryption path was
     * rewritten underneath it.
     */
    @Test
    void aCiphertextMovedToAnotherTokenDoesNotDecrypt() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);

        assertThatThrownBy(() -> cipher.decrypt(sealed, "tok_SOMEBODY_ELSES_ROW"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("card decryption failed");
    }

    @Test
    void aTamperedCiphertextIsRefusedRatherThanDecryptedToGarbage() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);
        sealed.ciphertext()[0] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(sealed, TOKEN))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Failure messages must never echo the card, the key or the ciphertext. */
    @Test
    void failureMessagesCarryNothingSensitive() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);

        assertThatThrownBy(() -> cipher.decrypt(sealed, "tok_wrong"))
                .hasMessageNotContaining(PAN)
                .hasMessageNotContaining("4242")
                .hasMessage("card decryption failed");
    }

    // --- rotation, which is what this is all for ---------------------------

    /**
     * THE HEADLINE.
     *
     * <p>A record encrypted under KEK v1, rotated to v2: the PAN ciphertext must
     * come out <strong>byte-identical</strong>. Only the wrapped DEK changes.
     *
     * <p>That is the difference between a rotation that re-wraps 44 bytes per
     * row and one that decrypts, re-encrypts and rewrites every card in the
     * vault - a long, stateful job holding plaintext PANs in memory, which is
     * why systems with a single applied key do not rotate at all.
     */
    @Test
    void rotatingTheKekDoesNotTouchASingleCardCiphertext() {
        EnvelopeCipher before = new EnvelopeCipher(beforeRotation);
        EnvelopeCipher.Sealed original = before.encrypt(PAN, TOKEN, SCOPE);
        byte[] ciphertextBefore = original.ciphertext().clone();
        byte[] ivBefore = original.iv().clone();

        // The ring gains v2 and makes it current. v1 stays, so nothing becomes
        // unreadable at the moment of rotation.
        EnvelopeCipher after = new EnvelopeCipher(afterRotation);
        KeyRing.WrappedKey rewrapped = after.rewrap(original.wrappedKey());

        EnvelopeCipher.Sealed rotated =
                new EnvelopeCipher.Sealed(original.iv(), original.ciphertext(), rewrapped);

        assertThat(rotated.ciphertext())
                .as("the encrypted card must be byte-identical - rotation never rewrites it")
                .isEqualTo(ciphertextBefore);
        assertThat(rotated.iv())
                .as("and its nonce is unchanged too, because it was not re-encrypted")
                .isEqualTo(ivBefore);

        assertThat(rewrapped.kekVersion())
                .as("only the wrapping moved")
                .isEqualTo("v2");
        assertThat(after.decrypt(rotated, TOKEN)).isEqualTo(PAN);
    }

    /**
     * The DEK survives rotation unchanged. It is the same key, wrapped
     * differently - which is why the ciphertext it produced stays valid.
     */
    @Test
    void theDataKeyItselfIsUnchangedByRotation() {
        EnvelopeCipher.Sealed original = new EnvelopeCipher(beforeRotation).encrypt(PAN, TOKEN, SCOPE);
        byte[] dekBefore = beforeRotation.unwrap(original.wrappedKey());

        KeyRing.WrappedKey rewrapped =
                new EnvelopeCipher(afterRotation).rewrap(original.wrappedKey());

        assertThat(afterRotation.unwrap(rewrapped)).isEqualTo(dekBefore);
    }

    /**
     * ROTATION HAS NO CUTOVER, which is what makes it safe to do gradually.
     *
     * <p>A record that has not been re-wrapped yet still reads, because its
     * wrapped DEK names v1 and v1 is still on the ring. So the ring can be
     * updated first and the re-wrap job can catch up over hours without any
     * window in which the vault is partly unreadable.
     */
    @Test
    void recordsNotYetRewrappedAreStillReadableAfterRotation() {
        EnvelopeCipher.Sealed old = new EnvelopeCipher(beforeRotation).encrypt(PAN, TOKEN, SCOPE);

        EnvelopeCipher after = new EnvelopeCipher(afterRotation);

        assertThat(after.decrypt(old, TOKEN))
                .as("an un-rotated record must not need the job to have reached it yet")
                .isEqualTo(PAN);
        assertThat(after.isCurrent(old.wrappedKey()))
                .as("but it is correctly identified as needing a re-wrap")
                .isFalse();
    }

    @Test
    void newRecordsAfterRotationUseTheNewVersionImmediately() {
        EnvelopeCipher after = new EnvelopeCipher(afterRotation);

        EnvelopeCipher.Sealed fresh = after.encrypt(PAN, TOKEN, SCOPE);

        assertThat(fresh.wrappedKey().kekVersion()).isEqualTo("v2");
        assertThat(after.isCurrent(fresh.wrappedKey())).isTrue();
    }

    /** Re-wrapping an already-current record is a no-op the job can skip. */
    @Test
    void anAlreadyRotatedRecordIsRecognisedAsCurrent() {
        EnvelopeCipher after = new EnvelopeCipher(afterRotation);
        EnvelopeCipher.Sealed fresh = after.encrypt(PAN, TOKEN, SCOPE);

        assertThat(after.isCurrent(fresh.wrappedKey())).isTrue();
    }

    /**
     * Retiring a KEK version makes everything wrapped under it unreadable - and
     * says so precisely rather than looking like corruption.
     *
     * <p>This is a failure mode in 9b and a <strong>feature</strong> in 9c:
     * destroying key material is exactly how crypto-shredding renders a
     * merchant's historical card data permanently meaningless without touching
     * a single downstream copy.
     */
    @Test
    void aRecordWrappedUnderARemovedKeyIsUnreadableAndSaysWhy() {
        EnvelopeCipher.Sealed old = new EnvelopeCipher(beforeRotation).encrypt(PAN, TOKEN, SCOPE);

        // v1 has been destroyed. Only v2 remains.
        KeyRing shredded = ring("v2", Map.of("v2", KEK_V2));

        assertThatThrownBy(() -> new EnvelopeCipher(shredded).decrypt(old, TOKEN))
                .isInstanceOf(KeyRing.UnknownKeyException.class)
                .hasMessageContaining("v1")
                .as("and it must not leak the ciphertext in the message")
                .hasMessageNotContaining(PAN);
    }

    // --- phase 9c: scope is the erasure boundary ---------------------------

    /**
     * THE CRYPTO-SHREDDING PROPERTY.
     *
     * <p>Two merchants, two scopes. Erasing one destroys that merchant's cards
     * permanently and leaves the other untouched - which is the whole reason
     * scope exists, and the thing a single shared KEK cannot do at all.
     */
    @Test
    void erasingOneScopeLeavesEveryOtherScopeIntact() {
        KeyRing ring = new KeyRing(new InMemoryKekStore(Map.of("v1", KEK_V1), "v1"));
        EnvelopeCipher cipher = new EnvelopeCipher(ring);

        EnvelopeCipher.Sealed alice = cipher.encrypt(PAN, TOKEN, "merchant-alice");
        EnvelopeCipher.Sealed bob = cipher.encrypt("4111111111111111", TOKEN, "merchant-bob");

        assertThat(cipher.forget("merchant-alice"))
                .as("one key version destroyed")
                .isEqualTo(1);

        assertThatThrownBy(() -> cipher.decrypt(alice, TOKEN))
                .as("the erased merchant's card is gone, permanently")
                .isInstanceOf(KeyRing.UnknownKeyException.class);

        assertThat(cipher.decrypt(bob, TOKEN))
                .as("and nobody else was affected")
                .isEqualTo("4111111111111111");
    }

    /**
     * The ciphertext is untouched by erasure, and that is the point rather than
     * an oversight.
     *
     * <p>Nothing goes looking for copies. The bytes stay exactly where they were
     * - in the table, in the replica, in last night's backup - and every one of
     * them is now meaningless. That is what makes erasure survive a restore,
     * which a DELETE does not.
     */
    @Test
    void erasureDestroysTheKeyAndNotTheData() {
        KeyRing ring = new KeyRing(new InMemoryKekStore(Map.of("v1", KEK_V1), "v1"));
        EnvelopeCipher cipher = new EnvelopeCipher(ring);
        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, "merchant-alice");
        byte[] ciphertextBefore = sealed.ciphertext().clone();

        cipher.forget("merchant-alice");

        assertThat(sealed.ciphertext())
                .as("the data is still there and is now permanently meaningless")
                .isEqualTo(ciphertextBefore);
    }

    /**
     * Erasing a scope erases EVERY version of its key, not just the current one.
     *
     * <p>A merchant whose key has been rotated three times has three versions,
     * with records under each. An erasure that destroyed only the current one
     * would leave most of their cards readable while reporting success.
     */
    @Test
    void erasureDestroysEveryVersionOfAScopeNotJustTheCurrentOne() {
        KeyRing ring = new KeyRing(new InMemoryKekStore(Map.of("v1", KEK_V1), "v1"));
        EnvelopeCipher cipher = new EnvelopeCipher(ring);

        EnvelopeCipher.Sealed first = cipher.encrypt(PAN, TOKEN, "merchant-alice");
        cipher.rotate("merchant-alice");
        EnvelopeCipher.Sealed second = cipher.encrypt(PAN, TOKEN, "merchant-alice");

        assertThat(first.wrappedKey().kekVersion())
                .isNotEqualTo(second.wrappedKey().kekVersion());

        assertThat(cipher.forget("merchant-alice")).isEqualTo(2);

        assertThatThrownBy(() -> cipher.decrypt(first, TOKEN))
                .isInstanceOf(KeyRing.UnknownKeyException.class);
        assertThatThrownBy(() -> cipher.decrypt(second, TOKEN))
                .isInstanceOf(KeyRing.UnknownKeyException.class);
    }

    /**
     * A wrapped DEK relabelled into another scope does not unwrap, because the
     * scope is bound into the wrap AAD.
     *
     * <p>Without that binding, somebody with write access could move an erased
     * merchant's row into a live merchant's scope and resurrect a card that had
     * been erased - turning a completed erasure request back into a data breach
     * with one UPDATE.
     */
    @Test
    void aWrappedKeyRelabelledIntoAnotherScopeDoesNotUnwrap() {
        KeyRing ring = new KeyRing(new InMemoryKekStore(Map.of("v1", KEK_V1), "v1"));
        EnvelopeCipher cipher = new EnvelopeCipher(ring);

        EnvelopeCipher.Sealed alice = cipher.encrypt(PAN, TOKEN, "merchant-alice");
        // Bob's scope must exist, or this fails for the uninteresting reason.
        cipher.encrypt("4111111111111111", TOKEN, "merchant-bob");

        KeyRing.WrappedKey relabelled = new KeyRing.WrappedKey(
                "merchant-bob", alice.wrappedKey().kekVersion(),
                alice.wrappedKey().iv(), alice.wrappedKey().ciphertext());

        assertThatThrownBy(() -> cipher.decrypt(
                new EnvelopeCipher.Sealed(alice.iv(), alice.ciphertext(), relabelled), TOKEN))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A scope's first key is created on first use.
     *
     * <p>Rather than at merchant onboarding: a merchant who has never taken a
     * payment has nothing to protect and nothing to erase, and provisioning keys
     * for them is key material existing for no reason.
     */
    @Test
    void aScopeGetsItsFirstKeyOnFirstUse() {
        KeyRing ring = new KeyRing(new InMemoryKekStore(Map.of("v1", KEK_V1), "v1"));

        assertThat(ring.currentVersion("merchant-new")).isEmpty();

        new EnvelopeCipher(ring).encrypt(PAN, TOKEN, "merchant-new");

        assertThat(ring.currentVersion("merchant-new")).isPresent();
    }

    /**
     * Erasing a scope that never existed is not an error. An erasure request for
     * a merchant who never took a payment is satisfied by there being nothing to
     * erase, and answering it with an exception would make the caller
     * distinguish "erased" from "nothing to erase" - a distinction the whole
     * point is to remove.
     */
    @Test
    void erasingAScopeThatNeverExistedIsHarmless() {
        KeyRing ring = new KeyRing(new InMemoryKekStore(Map.of("v1", KEK_V1), "v1"));

        assertThat(ring.forget("merchant-who-never-paid")).isZero();
    }

    // --- the store's own guards -------------------------------------------

    @Test
    void aStoreWhoseCurrentSharedVersionIsMissingRefusesToExist() {
        assertThatThrownBy(() -> new InMemoryKekStore(Map.of("v1", KEK_V1), "v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v2")
                .hasMessageContaining("not configured");
    }

    @Test
    void aKeyOfTheWrongLengthIsRefusedWithItsVersionNamed() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new InMemoryKekStore(Map.of("v9", tooShort), "v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v9")
                .hasMessageContaining("32 bytes");
    }

    /**
     * A key handed out by the store is a COPY.
     *
     * <p>{@code EnvelopeCipher} wipes the KEK array it is given as soon as it is
     * done with it - correct, and catastrophic if that array is the stored one.
     * The second encryption under the same scope would then wrap under 32 zero
     * bytes, and every record after that would be readable by anybody who
     * guessed. The wipe is right; the store cloning is what makes it safe.
     */
    @Test
    void theStoreHandsOutCopiesSoACallersWipeCannotShredAScope() {
        InMemoryKekStore store = new InMemoryKekStore(Map.of("v1", KEK_V1), "v1");
        EnvelopeCipher cipher = new EnvelopeCipher(new KeyRing(store));

        EnvelopeCipher.Sealed first = cipher.encrypt(PAN, TOKEN, SCOPE);
        EnvelopeCipher.Sealed second = cipher.encrypt(PAN, TOKEN, SCOPE);

        assertThat(cipher.decrypt(first, TOKEN)).isEqualTo(PAN);
        assertThat(cipher.decrypt(second, TOKEN))
                .as("the second wrap must not have used a wiped key")
                .isEqualTo(PAN);
    }

    /**
     * Domain separation: a wrapped DEK and an encrypted PAN are both AES-GCM
     * ciphertexts, and feeding one to the other's path must fail rather than
     * produce something.
     */
    @Test
    void aWrappedKeyCannotBeFedThroughTheCardDecryptionPath() {
        EnvelopeCipher cipher = new EnvelopeCipher(beforeRotation);
        EnvelopeCipher.Sealed sealed = cipher.encrypt(PAN, TOKEN, SCOPE);

        EnvelopeCipher.Sealed confused = new EnvelopeCipher.Sealed(
                sealed.wrappedKey().iv(), sealed.wrappedKey().ciphertext(), sealed.wrappedKey());

        assertThatThrownBy(() -> cipher.decrypt(confused, TOKEN))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- helper ------------------------------------------------------------

    /** Attempts to use raw stored bytes as an AES key, which must not work. */
    private static void decryptWithRawKey(EnvelopeCipher.Sealed sealed, byte[] rawKey)
            throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(rawKey, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, sealed.iv()));
        cipher.updateAAD(TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        cipher.doFinal(sealed.ciphertext());
    }
}
