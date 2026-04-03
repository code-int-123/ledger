# Ledger

A Spring Boot REST API for managing current accounts and recording financial transactions (deposits, withdrawals, and transfers).

## Tech Stack

- **Java 21**
- **Spring Boot 4.0.5**
- **OpenAPI Generator** — API interfaces and models generated from `api.yaml`
- **Maven**

## Getting Started

### Prerequisites

- Java 21+
- Maven (or use the included `./mvnw` wrapper)

### Run

```bash
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`.

### Test

```bash
./mvnw test
```

## API

The full contract is defined in [`src/main/resources/api.yaml`](src/main/resources/api.yaml).

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/accounts/current` | Open a current account |
| `GET` | `/accounts/current/{accountId}/transactions` | Get all transactions for an account |
| `POST` | `/accounts/current/{accountId}/deposit` | Deposit funds |
| `POST` | `/accounts/current/{accountId}/withdraw` | Withdraw funds |
| `POST` | `/accounts/current/{accountId}/transfer` | Transfer funds to another account |

### Example Requests

**Open an account**
```bash
curl -X POST http://localhost:8080/accounts/current \
  -H "Content-Type: application/json" \
  -d '{"userId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"}'
```

**Deposit**
```bash
curl -X POST http://localhost:8080/accounts/current/{accountId}/deposit \
  -H "Content-Type: application/json" \
  -d '{"depositId": "a1b2c3d4-0000-0000-0000-000000000001", "amount": 100.00}'
```

**Withdraw**
```bash
curl -X POST http://localhost:8080/accounts/current/{accountId}/withdraw \
  -H "Content-Type: application/json" \
  -d '{"withdrawId": "a1b2c3d4-0000-0000-0000-000000000002", "amount": 50.00}'
```

**Transfer**
```bash
curl -X POST http://localhost:8080/accounts/current/{accountId}/transfer \
  -H "Content-Type: application/json" \
  -d '{"transferId": "a1b2c3d4-0000-0000-0000-000000000003", "targetAccountId": "{targetAccountId}", "amount": 25.00}'
```

**Get transactions**
```bash
curl http://localhost:8080/accounts/current/{accountId}/transactions
```

### Error Responses

All errors return an `ErrorResponse` with a `message` field.

| Status | Meaning |
|--------|---------|
| `400` | Invalid request (missing or malformed fields) |
| `404` | Account not found |
| `422` | Insufficient balance |

## Design Notes

### Idempotency

Every mutating operation accepts a caller-supplied UUID as an idempotency key (`depositId`, `withdrawId`, `transferId`). Resubmitting the same key returns the original transaction record without re-applying the operation, making retries safe.

### One Account Per User

Each user may open at most one current account. Calling `POST /accounts/current` with the same `userId` returns the existing account ID.

### Transfers

A single transfer creates two transaction records — `TRANSFER_OUT` on the source account and `TRANSFER_IN` on the target — both sharing the same `transferId`.

### Thread Safety

All balance mutations (`deposit`, `withdraw`, `transfer`) are executed inside a single `synchronized` block on the service instance, ensuring that concurrent requests are serialised and balances are never corrupted.

### Storage

All data is held in memory (`ConcurrentHashMap`). State does not survive a restart.

## Project Structure

```
src/
├── main/
│   ├── java/com/teya/ledger/
│   │   ├── LedgerApplication.java
│   │   ├── controller/
│   │   │   ├── CurrentAccountController.java
│   │   │   └── GlobalExceptionHandler.java
│   │   ├── exception/
│   │   │   ├── AccountNotFoundException.java
│   │   │   └── InsufficientBalanceException.java
│   │   ├── model/
│   │   │   ├── CurrentAccount.java
│   │   │   └── TransactionRecord.java
│   │   └── service/
│   │       └── CurrentAccountService.java
│   └── resources/
│       └── api.yaml
└── test/
    └── java/com/teya/ledger/
        ├── e2e/CurrentAccountE2ETest.java
        └── service/CurrentAccountServiceTest.java
```
