package com.teya.ledger.service;

import com.teya.ledger.exception.AccountNotFoundException;
import com.teya.ledger.exception.InsufficientBalanceException;
import com.teya.ledger.model.TransactionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentAccountServiceTest {

    private CurrentAccountService service;

    @BeforeEach
    void setUp() {
        service = new CurrentAccountService();
    }

    @Nested
    class OpenCurrentAccount {

        @Test
        void returnsAccountId() {
            UUID userId = UUID.randomUUID();
            UUID accountId = service.openCurrentAccount(userId);
            assertThat(accountId).isNotNull();
        }

        @Test
        void sameUserReturnsSameAccountId() {
            UUID userId = UUID.randomUUID();
            UUID first  = service.openCurrentAccount(userId);
            UUID second = service.openCurrentAccount(userId);
            assertThat(first).isEqualTo(second);
        }

        @Test
        void differentUsersGetDifferentAccounts() {
            UUID accountA = service.openCurrentAccount(UUID.randomUUID());
            UUID accountB = service.openCurrentAccount(UUID.randomUUID());
            assertThat(accountA).isNotEqualTo(accountB);
        }
    }

    @Nested
    class Deposit {

        private UUID accountId;

        @BeforeEach
        void openAccount() {
            accountId = service.openCurrentAccount(UUID.randomUUID());
        }

        @Test
        void recordsDepositTransaction() {
            UUID depositId = UUID.randomUUID();
            TransactionRecord tx = service.deposit(depositId, accountId, new BigDecimal("100.00"));

            assertThat(tx.getTransactionId()).isEqualTo(depositId);
            assertThat(tx.getType()).isEqualTo(TransactionRecord.Type.DEPOSIT);
            assertThat(tx.getAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        void updatesBalance() {
            service.deposit(UUID.randomUUID(), accountId, new BigDecimal("50.00"));
            service.deposit(UUID.randomUUID(), accountId, new BigDecimal("30.00"));

            TransactionRecord tx = service.deposit(UUID.randomUUID(), accountId, new BigDecimal("20.00"));
            assertThat(service.getTransactions(accountId)).hasSize(3);
        }

        @Test
        void idempotentDepositReturnsSameRecord() {
            UUID depositId = UUID.randomUUID();
            TransactionRecord first  = service.deposit(depositId, accountId, new BigDecimal("100.00"));
            TransactionRecord second = service.deposit(depositId, accountId, new BigDecimal("100.00"));

            assertThat(first).isSameAs(second);
            assertThat(service.getTransactions(accountId)).hasSize(1);
        }

        @Test
        void throwsWhenAccountNotFound() {
            UUID unknownAccountId = UUID.randomUUID();
            assertThatThrownBy(() -> service.deposit(UUID.randomUUID(), unknownAccountId, new BigDecimal("100.00")))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void transactionHasTimestamp() {
            TransactionRecord tx = service.deposit(UUID.randomUUID(), accountId, new BigDecimal("100.00"));
            assertThat(tx.getTimestamp()).isNotNull();
        }

        @Test
        void transactionIsStoredInMap() {
            UUID depositId = UUID.randomUUID();
            service.deposit(depositId, accountId, new BigDecimal("100.00"));
            assertThat(service.getTransactions(accountId)).containsKey(depositId);
        }

        @Test
        void multipleDepositsAccumulateBalance() {
            service.deposit(UUID.randomUUID(), accountId, new BigDecimal("100.00"));
            service.deposit(UUID.randomUUID(), accountId, new BigDecimal("50.00"));
            // total is 150 — withdrawing 150 should succeed, 150.01 should fail
            service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("150.00"));
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void idempotentDepositDoesNotDoubleCredit() {
            UUID depositId = UUID.randomUUID();
            service.deposit(depositId, accountId, new BigDecimal("100.00"));
            service.deposit(depositId, accountId, new BigDecimal("100.00"));
            // balance is 100, not 200 — withdrawing 100.01 should fail
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("100.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void newAccountHasZeroBalance() {
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void concurrentDepositsAreThreadSafe() throws InterruptedException {
            int threads = 50;
            BigDecimal amount = new BigDecimal("10.00");
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        service.deposit(UUID.randomUUID(), accountId, amount);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            executor.shutdown();

            // All 50 deposits of 10 = 500 total; withdrawing 500 should succeed
            service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("500.00"));
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    @Nested
    class Withdraw {

        private UUID accountId;

        @BeforeEach
        void openAccountWithFunds() {
            accountId = service.openCurrentAccount(UUID.randomUUID());
            service.deposit(UUID.randomUUID(), accountId, new BigDecimal("200.00"));
        }

        @Test
        void recordsWithdrawTransaction() {
            UUID withdrawId = UUID.randomUUID();
            TransactionRecord tx = service.withdraw(withdrawId, accountId, new BigDecimal("50.00"));

            assertThat(tx.getTransactionId()).isEqualTo(withdrawId);
            assertThat(tx.getType()).isEqualTo(TransactionRecord.Type.WITHDRAW);
            assertThat(tx.getAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        void idempotentWithdrawReturnsSameRecord() {
            UUID withdrawId = UUID.randomUUID();
            TransactionRecord first  = service.withdraw(withdrawId, accountId, new BigDecimal("50.00"));
            TransactionRecord second = service.withdraw(withdrawId, accountId, new BigDecimal("50.00"));

            assertThat(first).isSameAs(second);
            assertThat(service.getTransactions(accountId)).hasSize(2); // deposit + withdraw
        }

        @Test
        void throwsWhenInsufficientBalance() {
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("999.00")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void throwsWhenAccountNotFound() {
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.00")))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void canWithdrawExactBalance() {
            service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("200.00"));
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void transactionIsStoredInMap() {
            UUID withdrawId = UUID.randomUUID();
            service.withdraw(withdrawId, accountId, new BigDecimal("50.00"));
            assertThat(service.getTransactions(accountId)).containsKey(withdrawId);
        }

        @Test
        void balanceReducedAfterWithdraw() {
            service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("150.00"));
            // remaining balance is 50; withdrawing 50 succeeds, 50.01 fails
            service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("50.00"));
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void idempotentWithdrawDoesNotDoubleDebit() {
            UUID withdrawId = UUID.randomUUID();
            service.withdraw(withdrawId, accountId, new BigDecimal("100.00"));
            service.withdraw(withdrawId, accountId, new BigDecimal("100.00"));
            // balance is 100 remaining (debited once), not 0 — can still withdraw 100
            service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("100.00"));
        }

        @Test
        void concurrentWithdrawsRespectBalance() throws InterruptedException {
            // balance is 200; fire 20 withdrawals of 20 each (total 400) — only 10 should succeed
            int threads = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        service.withdraw(UUID.randomUUID(), accountId, new BigDecimal("20.00"));
                        successes.incrementAndGet();
                    } catch (InsufficientBalanceException e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            executor.shutdown();

            assertThat(successes.get()).isEqualTo(10);
            assertThat(failures.get()).isEqualTo(10);
        }
    }

    @Nested
    class Transfer {

        private UUID sourceAccountId;
        private UUID targetAccountId;

        @BeforeEach
        void openAccounts() {
            sourceAccountId = service.openCurrentAccount(UUID.randomUUID());
            targetAccountId = service.openCurrentAccount(UUID.randomUUID());
            service.deposit(UUID.randomUUID(), sourceAccountId, new BigDecimal("500.00"));
        }

        @Test
        void recordsTransferOutOnSource() {
            UUID transferId = UUID.randomUUID();
            TransactionRecord tx = service.transfer(transferId, sourceAccountId, targetAccountId, new BigDecimal("100.00"));

            assertThat(tx.getTransactionId()).isEqualTo(transferId);
            assertThat(tx.getType()).isEqualTo(TransactionRecord.Type.TRANSFER_OUT);
            assertThat(tx.getAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        void recordsTransferInOnTarget() {
            UUID transferId = UUID.randomUUID();
            service.transfer(transferId, sourceAccountId, targetAccountId, new BigDecimal("100.00"));

            TransactionRecord inTx = service.getTransactions(targetAccountId).get(transferId);
            assertThat(inTx).isNotNull();
            assertThat(inTx.getType()).isEqualTo(TransactionRecord.Type.TRANSFER_IN);
            assertThat(inTx.getAmount()).isEqualByComparingTo("100.00");
        }

        @Test
        void updatesBalancesOnBothAccounts() {
            service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("200.00"));

            // verify via transaction counts: source has deposit + transfer_out, target has transfer_in
            assertThat(service.getTransactions(sourceAccountId)).hasSize(2);
            assertThat(service.getTransactions(targetAccountId)).hasSize(1);
        }

        @Test
        void idempotentTransferReturnsSameRecord() {
            UUID transferId = UUID.randomUUID();
            TransactionRecord first  = service.transfer(transferId, sourceAccountId, targetAccountId, new BigDecimal("100.00"));
            TransactionRecord second = service.transfer(transferId, sourceAccountId, targetAccountId, new BigDecimal("100.00"));

            assertThat(first).isSameAs(second);
            assertThat(service.getTransactions(sourceAccountId)).hasSize(2);
        }

        @Test
        void throwsWhenSourceNotFound() {
            assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), UUID.randomUUID(), targetAccountId, new BigDecimal("100.00")))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void throwsWhenTargetNotFound() {
            assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), sourceAccountId, UUID.randomUUID(), new BigDecimal("100.00")))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void throwsWhenInsufficientBalance() {
            assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("999.00")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void sourceAndTargetShareSameTransferId() {
            UUID transferId = UUID.randomUUID();
            service.transfer(transferId, sourceAccountId, targetAccountId, new BigDecimal("100.00"));

            assertThat(service.getTransactions(sourceAccountId)).containsKey(transferId);
            assertThat(service.getTransactions(targetAccountId)).containsKey(transferId);
        }

        @Test
        void canTransferExactBalance() {
            service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("500.00"));
            assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void targetCanWithdrawReceivedFunds() {
            service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("300.00"));
            // target received 300; withdrawing 300 should succeed
            service.withdraw(UUID.randomUUID(), targetAccountId, new BigDecimal("300.00"));
            assertThatThrownBy(() -> service.withdraw(UUID.randomUUID(), targetAccountId, new BigDecimal("0.01")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void sourceBalanceReducedAfterTransfer() {
            service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("400.00"));
            // source has 100 left; transferring 200 should fail
            assertThatThrownBy(() -> service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("200.00")))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        void concurrentTransfersAreThreadSafe() throws InterruptedException {
            // source has 500; fire 10 concurrent transfers of 100 each — only 5 should succeed
            int threads = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        service.transfer(UUID.randomUUID(), sourceAccountId, targetAccountId, new BigDecimal("100.00"));
                        successes.incrementAndGet();
                    } catch (InsufficientBalanceException e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            executor.shutdown();

            assertThat(successes.get()).isEqualTo(5);
            assertThat(failures.get()).isEqualTo(5);
        }
    }

    @Nested
    class GetTransactions {

        @Test
        void returnsEmptyMapForNewAccount() {
            UUID accountId = service.openCurrentAccount(UUID.randomUUID());
            assertThat(service.getTransactions(accountId)).isEmpty();
        }

        @Test
        void throwsWhenAccountNotFound() {
            assertThatThrownBy(() -> service.getTransactions(UUID.randomUUID()))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        void returnsDefensiveCopy() {
            UUID accountId = service.openCurrentAccount(UUID.randomUUID());
            service.deposit(UUID.randomUUID(), accountId, new BigDecimal("100.00"));

            var copy = service.getTransactions(accountId);
            assertThatThrownBy(() -> copy.put(UUID.randomUUID(), null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
