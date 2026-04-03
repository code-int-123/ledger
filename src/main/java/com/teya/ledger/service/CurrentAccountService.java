package com.teya.ledger.service;

import com.teya.ledger.exception.AccountNotFoundException;
import com.teya.ledger.exception.InsufficientBalanceException;
import com.teya.ledger.model.CurrentAccount;
import com.teya.ledger.model.TransactionRecord;
import com.teya.ledger.model.TransactionRecord.Type;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CurrentAccountService {

    // userId -> accountId
    private final Map<UUID, UUID> accountsByUserId = new ConcurrentHashMap<>();

    // accountId -> CurrentAccount (for fast lookup by accountId)
    private final Map<UUID, CurrentAccount> accountsById = new ConcurrentHashMap<>();

    // accountId -> (transactionId -> TransactionRecord)
    private final Map<UUID, Map<UUID, TransactionRecord>> transactionsByAccountId = new ConcurrentHashMap<>();


    /**
     * Opens a current account for the given user.
     * If the user already has an account, the existing account ID is returned (idempotent).
     *
     * @param userId the ID of the user opening the account
     * @return the account ID, either newly created or pre-existing
     */
    public UUID openCurrentAccount(UUID userId) {
        return accountsByUserId.computeIfAbsent(userId, id -> {
            CurrentAccount account = new CurrentAccount(UUID.randomUUID(), id);
            accountsById.put(account.getAccountId(), account);
            transactionsByAccountId.put(account.getAccountId(), new ConcurrentHashMap<>());
            return account.getAccountId();
        });
    }

    /**
     * Credits the given amount to an account.
     * If a deposit with the same {@code depositId} has already been processed, the original
     * transaction record is returned without modifying the balance (idempotent).
     *
     * @param depositId idempotency key; used as the transaction ID
     * @param accountId the account to credit
     * @param amount    the amount to deposit; must be positive
     * @return the resulting {@link TransactionRecord}
     * @throws AccountNotFoundException if {@code accountId} does not exist
     */
    public TransactionRecord deposit(UUID depositId, UUID accountId, BigDecimal amount) {
        CurrentAccount account = Optional.ofNullable(accountsById.get(accountId))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        Map<UUID, TransactionRecord> transactions = transactionsByAccountId.get(accountId);
        synchronized (this) {
            TransactionRecord existing = transactions.get(depositId);
            if (existing != null) return existing;
            TransactionRecord tx = new TransactionRecord(depositId, Type.DEPOSIT, amount);
            account.credit(amount);
            transactions.put(depositId, tx);
            return tx;
        }
    }

    /**
     * Debits the given amount from an account.
     * If a withdrawal with the same {@code withdrawId} has already been processed, the original
     * transaction record is returned without modifying the balance (idempotent).
     *
     * @param withdrawId idempotency key; used as the transaction ID
     * @param accountId  the account to debit
     * @param amount     the amount to withdraw; must be positive
     * @return the resulting {@link TransactionRecord}
     * @throws AccountNotFoundException    if {@code accountId} does not exist
     * @throws InsufficientBalanceException if the account balance is less than {@code amount}
     */
    public TransactionRecord withdraw(UUID withdrawId, UUID accountId, BigDecimal amount) {
        CurrentAccount account = Optional.ofNullable(accountsById.get(accountId))
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        Map<UUID, TransactionRecord> transactions = transactionsByAccountId.get(accountId);
        synchronized (this) {
            TransactionRecord existing = transactions.get(withdrawId);
            if (existing != null) {
                return existing;
            }
            if (account.getBalance().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(accountId, account.getBalance(), amount);
            }
            TransactionRecord tx = new TransactionRecord(withdrawId, Type.WITHDRAW, amount);
            account.debt(amount);
            transactions.put(withdrawId, tx);
            return tx;
        }
    }

    /**
     * Transfers the given amount from one account to another.
     * A {@link TransactionRecord} of type {@code TRANSFER_OUT} is recorded on the source and
     * {@code TRANSFER_IN} on the target, both sharing the same {@code transferId}.
     * If a transfer with the same {@code transferId} has already been processed, the original
     * source transaction record is returned without any balance changes (idempotent).
     *
     * @param transferId      idempotency key; used as the transaction ID on both accounts
     * @param sourceAccountId the account to debit
     * @param targetAccountId the account to credit
     * @param amount          the amount to transfer; must be positive
     * @return the {@link TransactionRecord} of type {@code TRANSFER_OUT} recorded on the source
     * @throws AccountNotFoundException     if either account does not exist
     * @throws InsufficientBalanceException if the source balance is less than {@code amount}
     */
    public TransactionRecord transfer(UUID transferId, UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {
        CurrentAccount source = Optional.ofNullable(accountsById.get(sourceAccountId))
                .orElseThrow(() -> new AccountNotFoundException(sourceAccountId));
        CurrentAccount target = Optional.ofNullable(accountsById.get(targetAccountId))
                .orElseThrow(() -> new AccountNotFoundException(targetAccountId));
        Map<UUID, TransactionRecord> sourceTransactions = transactionsByAccountId.get(sourceAccountId);
        Map<UUID, TransactionRecord> targetTransactions = transactionsByAccountId.get(targetAccountId);
        synchronized (this) {
            TransactionRecord existing = sourceTransactions.get(transferId);
            if (existing != null) return existing;
            if (source.getBalance().compareTo(amount) < 0) {
                throw new InsufficientBalanceException(sourceAccountId, source.getBalance(), amount);
            }
            TransactionRecord outTx = new TransactionRecord(transferId, Type.TRANSFER_OUT, amount);
            TransactionRecord inTx  = new TransactionRecord(transferId, Type.TRANSFER_IN, amount);
            source.debt(amount);
            target.credit(amount);
            sourceTransactions.put(transferId, outTx);
            targetTransactions.put(transferId, inTx);
            return outTx;
        }
    }

    /**
     * Returns an unmodifiable snapshot of all transactions recorded against an account,
     * keyed by transaction ID.
     *
     * @param accountId the account to query
     * @return an unmodifiable map of transaction ID to {@link TransactionRecord}
     * @throws AccountNotFoundException if {@code accountId} does not exist
     */
    public Map<UUID, TransactionRecord> getTransactions(UUID accountId) {
        Map<UUID, TransactionRecord> transactions = transactionsByAccountId.get(accountId);
        if (transactions == null) {
            throw new AccountNotFoundException(accountId);
        }
        return Map.copyOf(transactions);
    }

}