-- Phase 9d. Where key-encryption keys live.
--
-- WHY THIS EXISTS AT ALL
--
-- Phases 9b and 9c built envelope encryption and per-merchant key scoping, and
-- both shipped with an in-memory KekStore whose javadoc said it was "honest for
-- local development and useless for anything else". The cost it named was that
-- keys die on restart. The cost it did NOT name is the one that mattered: with
-- more than one process, a key minted in one JVM is invisible in the other.
--
-- payments-edge tokenizes a card and mints the merchant's KEK in its own heap.
-- psp-connector detokenizes the same card and looks that scope up in a heap
-- that has never heard of it. Every payment on the live stack failed with
--
--     KeyRing$UnknownKeyException: no key material for scope
--     '0192abcd-...' version 'v1' - it was never created, or it has been erased
--
-- and it stayed broken from 9b to the end of the roadmap because the stack was
-- never started again after the change.
--
-- WHY A SEPARATE DATABASE AND A SEPARATE ACCOUNT
--
-- KekStore's contract states the one rule any implementation has to obey: key
-- material must not share a backup domain with the ciphertext it protects, or
-- destroying the key erases nothing.
--
-- A separate schema with disjoint grants is a WEAKER form of that rule, and the
-- weakness should be stated rather than glossed. It buys the grant half: no
-- vault credential can read a KEK, and no KEK credential can read a card, so
-- compromising one account does not yield plaintext. It does not buy the backup
-- half - one mysqldump of this instance still captures both, which is exactly
-- the scenario crypto-shredding is supposed to survive.
--
-- Vault or a cloud KMS is the production answer and remains outstanding. This is
-- the part that can be enforced with the infrastructure this project actually
-- runs, and it is a real improvement over a HashMap.

CREATE DATABASE IF NOT EXISTS payorch_kek
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS payorch_kek.kek_material (
    -- The erasure boundary. A merchant id, or KeyRing.SHARED_SCOPE for records
    -- that opted out of scoping. Phase 9c's argument for why this is the
    -- merchant and not something coarser is in KeyRing's javadoc.
    scope        VARCHAR(64)   NOT NULL,

    -- Several versions live at once per scope. Exactly one is current and wraps
    -- new records; the rest exist only to unwrap what they already wrapped,
    -- which is what makes rotation something other than "rewrite every card".
    version      VARCHAR(32)   NOT NULL,

    -- 32 bytes, AES-256. VARBINARY and not a string: base64 in a column invites
    -- somebody to log the row.
    key_material VARBINARY(64) NOT NULL,

    is_current   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    -- (scope, version) is the natural key and the thing two racing processes
    -- collide on. JdbcKekStore relies on this constraint rather than on a
    -- check-then-insert: the loser of the race must adopt the winner's key, and
    -- a duplicate-key error is the only reliable way to learn that it lost.
    PRIMARY KEY (scope, version),

    -- The current-version lookup happens on every tokenize.
    KEY idx_kek_current (scope, is_current)
) ENGINE = InnoDB;

-- The only account that ever touches key material. Deliberately NOT the vault
-- account: the whole point is that no single credential yields both the
-- ciphertext and the key that opens it.
--
-- DELETE is granted, and it is the grant that makes 9c's crypto-shredding real
-- rather than rhetorical. Erasure is a delete here, and the card ciphertext is
-- left exactly where it is - permanently unreadable, including in the backups
-- nobody can go and find.
CREATE USER IF NOT EXISTS 'kek_user'@'%' IDENTIFIED BY 'kek_user_pw';
GRANT SELECT, INSERT, UPDATE, DELETE ON payorch_kek.kek_material TO 'kek_user'@'%';

-- Note what is NOT here: any grant on payorch_vault. That is the whole control,
-- and it is expressed as an absence rather than as a REVOKE.
--
-- The first version of this file ended with
--
--     REVOKE ALL PRIVILEGES ON payorch_vault.* FROM 'kek_user'@'%';
--
-- which reads as belt-and-braces and is actually a landmine: MySQL raises
-- ERROR 1141 when revoking a grant that was never issued, so on a fresh
-- database the statement fails, and because it sits at the end of an init
-- script the failure aborts the rest of it. A "defensive" line that breaks
-- initialisation on exactly the clean install it was meant to protect.
--
-- The grant above is the enforcement. `kek_user` has USAGE on *.* and SELECT,
-- INSERT, UPDATE, DELETE on one table; there is nothing to revoke because
-- nothing was ever given.

FLUSH PRIVILEGES;
