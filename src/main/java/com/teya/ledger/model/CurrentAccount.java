package com.teya.ledger.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public class CurrentAccount {

    private final UUID accountId;
    private final UUID userId;
    private BigDecimal balance;

    public CurrentAccount(UUID accountId, UUID userId) {
        this.accountId = accountId;
        this.userId = userId;
        this.balance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount).setScale(2, RoundingMode.HALF_UP);
    }

    public void debt(BigDecimal amount) {
        balance = balance.subtract(amount).setScale(2, RoundingMode.HALF_UP);
    }
}