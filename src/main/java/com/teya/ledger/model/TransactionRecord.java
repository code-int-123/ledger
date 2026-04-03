package com.teya.ledger.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TransactionRecord implements Comparable<TransactionRecord> {

    public enum Type {
        DEPOSIT, WITHDRAW, TRANSFER_OUT, TRANSFER_IN
    }

    private final UUID transactionId;
    private final Type type;
    private final BigDecimal amount;
    private final Instant timestamp;

    public TransactionRecord(UUID transactionId, Type type, BigDecimal amount) {
        this.transactionId = transactionId;
        this.type = type;
        this.amount = amount;
        this.timestamp = Instant.now();
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Type getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public int compareTo(TransactionRecord other) {
        int cmp = this.timestamp.compareTo(other.timestamp);
        return cmp != 0 ? cmp : this.transactionId.compareTo(other.transactionId);
    }


}