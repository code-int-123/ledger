package com.teya.ledger.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(UUID accountId, BigDecimal balance, BigDecimal amount) {
        super("Insufficient balance on account " + accountId + ": has " + balance + ", needs " + amount);
    }
}