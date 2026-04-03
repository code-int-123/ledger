package com.teya.ledger.controller;

import com.teya.ledger.model.TransactionRecord;
import com.teya.ledger.service.CurrentAccountService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.api.AccountsApi;
import org.openapitools.model.DepositRequest;
import org.openapitools.model.OpenCurrentAccountRequest;
import org.openapitools.model.OpenCurrentAccountResponse;
import org.openapitools.model.TransactionListResponse;
import org.openapitools.model.TransactionResponse;
import org.openapitools.model.TransferRequest;
import org.openapitools.model.WithdrawRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class CurrentAccountController implements AccountsApi {

    private final CurrentAccountService currentAccountService;

    @Override
    public ResponseEntity<OpenCurrentAccountResponse> openCurrentAccount(
            @Parameter(name = "OpenCurrentAccountRequest", description = "", required = true) @Valid @RequestBody OpenCurrentAccountRequest openCurrentAccountRequest
    ) {
        log.info("openCurrentAccount: userId={}", openCurrentAccountRequest.getUserId());
        UUID accountId = currentAccountService.openCurrentAccount(openCurrentAccountRequest.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new OpenCurrentAccountResponse().accountId(accountId));
    }

    @Override
    public ResponseEntity<TransactionListResponse> getTransactions(
            @PathVariable UUID accountId
    ) {
        log.info("getTransactions: accountId={}", accountId);
        List<TransactionResponse> txs = currentAccountService.getTransactions(accountId)
                .values().stream()
                .map(this::toTransactionResponse)
                .toList();
        return ResponseEntity.ok(new TransactionListResponse().transactions(txs));
    }

    @Override
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID accountId,
            @Valid @RequestBody DepositRequest depositRequest
    ) {
        log.info("deposit: accountId={}, depositId={}, amount={}", accountId, depositRequest.getDepositId(), depositRequest.getAmount());
        TransactionRecord tx = currentAccountService.deposit(depositRequest.getDepositId(), accountId, BigDecimal.valueOf(depositRequest.getAmount()));
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @Override
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @Valid @RequestBody WithdrawRequest withdrawRequest
    ) {
        log.info("withdraw: accountId={}, withdrawId={}, amount={}", accountId, withdrawRequest.getWithdrawId(), withdrawRequest.getAmount());
        TransactionRecord tx = currentAccountService.withdraw(withdrawRequest.getWithdrawId(), accountId, BigDecimal.valueOf(withdrawRequest.getAmount()));
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    @Override
    public ResponseEntity<TransactionResponse> transfer(
            @PathVariable UUID accountId,
            @Valid @RequestBody TransferRequest transferRequest
    ) {
        log.info("transfer: sourceAccountId={}, transferId={}, targetAccountId={}, amount={}",
                accountId, transferRequest.getTransferId(), transferRequest.getTargetAccountId(), transferRequest.getAmount());
        TransactionRecord tx = currentAccountService.transfer(
                transferRequest.getTransferId(), accountId,
                transferRequest.getTargetAccountId(),
                BigDecimal.valueOf(transferRequest.getAmount()));
        return ResponseEntity.ok(toTransactionResponse(tx));
    }

    private TransactionResponse toTransactionResponse(TransactionRecord tx) {
        return new TransactionResponse()
                .transactionId(tx.getTransactionId())
                .type(TransactionResponse.TypeEnum.valueOf(tx.getType().name()))
                .amount(tx.getAmount().doubleValue())
                .timestamp(tx.getTimestamp().atOffset(java.time.ZoneOffset.UTC));
    }
}
