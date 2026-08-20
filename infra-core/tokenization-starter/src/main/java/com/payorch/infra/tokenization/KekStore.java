package com.payorch.infra.tokenization;

import java.util.Optional;
import java.util.Set;

/**
 * Where key-encryption keys live. Phase 9c.
 *
 * <h2>The one requirement, and everything else follows from it</h2>
 *
 * <p><strong>An implementation MUST store keys somewhere that is not backed up
 * alongside the ciphertext they protect.</strong> That is not a
 * nice-to-have - it is the entire mechanism of crypto-shredding, and an
 * implementation that puts KEKs in the same MySQL instance as
 * {@code token_vault} provides none of it.
 *
 * <p>The reasoning is worth being slow about, because "we delete the mapping
 * row" sounds like it is enough and is not. Deleting a row does not remove it
 * from last night's backup, from a replica's binlog, or from a snapshot taken
 * for a migration. An erasure request that is satisfied by a {@code DELETE} is
 * satisfied only until somebody restores. Destroying the KEK instead makes every
 * copy of the ciphertext - live, replicated, backed up, or on a tape in a
 * cupboard - permanently undecryptable, and it does so without anybody needing
 * to find those copies.
 *
 * <p>Which is why the production implementation is Vault or a cloud KMS: the key
 * material lives in a different system with a different backup lifecycle, and
 * the unwrap happens inside it so the KEK never reaches this process at all.
 *
 * <h2>Why keys are generated and stored, never derived</h2>
 *
 * <p>The tempting alternative is to derive a per-merchant KEK from one master
 * key: {@code HKDF(master, merchantId)}. It needs no store, it is stateless, and
 * it is <strong>unshreddable</strong> - anyone holding the master can re-derive
 * a "destroyed" key at any time, so erasure is unachievable by construction.
 *
 * <p>A key that can be recomputed cannot be forgotten. If the erasure boundary
 * is the merchant, each merchant's key has to be independently generated and
 * independently destroyable, and that means it has to be stored.
 *
 * <h2>Scope is the erasure boundary</h2>
 *
 * <p>Whatever unit this system must be able to erase is the unit a scope has to
 * name. Here that is the merchant, because a right-to-erasure request arrives
 * for a merchant's data. Choosing the scope wrongly is not something a later
 * refactor fixes: records encrypted under a shared key cannot be separated
 * afterwards without decrypting and re-encrypting them, which is the expensive
 * migration everything in 9b was arranged to avoid.
 */
public interface KekStore {

    /**
     * The key for one scope and version, if it still exists.
     *
     * <p>Empty means shredded - or never created. The caller cannot tell those
     * apart and must not try: both mean the data is unreadable, and a store that
     * distinguished them would be answering "did this merchant once exist",
     * which is itself a question an erasure request asks us to stop being able
     * to answer.
     */
    Optional<byte[]> find(String scope, String version);

    /**
     * Creates a new current version for a scope, returning its version label.
     *
     * <p>Called on first use for a merchant, and again for each rotation.
     * Previous versions remain readable until they are forgotten - the same
     * no-cutover property 9b established, now per scope.
     */
    String createCurrentVersion(String scope);

    /** The version new records in this scope are wrapped under. */
    Optional<String> currentVersion(String scope);

    /**
     * <strong>Destroys every key for a scope. This is the erasure.</strong>
     *
     * <p>Irreversible by design and by definition: an implementation that could
     * undo it has not erased anything. Everything ever encrypted under this
     * scope becomes permanently unreadable at the moment this returns, in every
     * copy that exists anywhere, without any of those copies being touched.
     *
     * @return how many key versions were destroyed
     */
    int forget(String scope);

    /** Which scopes still have key material. For an operator, not for a request path. */
    Set<String> scopes();
}
