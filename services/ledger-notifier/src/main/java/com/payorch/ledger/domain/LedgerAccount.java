package com.payorch.ledger.domain;

import java.util.UUID;

import com.payorch.infra.persistence.Uuid7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A balance. See {@code V1__ledger.sql} for why balances are relational. */
@Entity
@Table(name = "ledger_account")
public class LedgerAccount {

    @Id
    @Column(name = "id", columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "account_ref", nullable = false)
    private String accountRef;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    protected LedgerAccount() {
    }

    public static LedgerAccount open(String accountRef, String currency) {
        LedgerAccount account = new LedgerAccount();
        account.id = Uuid7.generate();
        account.accountRef = accountRef;
        account.currency = currency;
        account.balanceMinor = 0;
        return account;
    }

    /**
     * Applies one leg.
     *
     * <p>Signed, so this is the same operation for a debit and a credit. The
     * caller cannot get the direction wrong separately from the amount, because
     * there is only one number.
     */
    public void apply(long amountMinor) {
        this.balanceMinor += amountMinor;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountRef() {
        return accountRef;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }
}
