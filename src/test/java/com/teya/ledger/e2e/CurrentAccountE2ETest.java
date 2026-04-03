package com.teya.ledger.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teya.ledger.controller.CurrentAccountController;
import com.teya.ledger.controller.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class CurrentAccountE2ETest {

    @Autowired
    private CurrentAccountController currentAccountController;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JsonNullableModule());

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(currentAccountController)
                .setControllerAdvice(globalExceptionHandler)
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    // --- helpers ---

    private UUID openAccount(UUID userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/accounts/current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", userId))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accountId").asText());
    }

    private void deposit(UUID accountId, UUID depositId, double amount) throws Exception {
        mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("depositId", depositId, "amount", amount))))
                .andExpect(status().isOk());
    }

    // --- tests ---

    @Nested
    class OpenAccount {

        @Test
        void returns201WithAccountId() throws Exception {
            mockMvc.perform(post("/accounts/current")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("userId", UUID.randomUUID()))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountId").isNotEmpty());
        }

        @Test
        void sameUserReturnsSameAccountId() throws Exception {
            UUID userId = UUID.randomUUID();
            UUID first  = openAccount(userId);
            UUID second = openAccount(userId);
            assertThat(first).isEqualTo(second);
        }

        @Test
        void differentUsersGetDifferentAccountIds() throws Exception {
            UUID first  = openAccount(UUID.randomUUID());
            UUID second = openAccount(UUID.randomUUID());
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        void returns400WhenUserIdMissing() throws Exception {
            mockMvc.perform(post("/accounts/current")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenBodyMissing() throws Exception {
            mockMvc.perform(post("/accounts/current")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenUserIdIsInvalidFormat() throws Exception {
            mockMvc.perform(post("/accounts/current")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": \"not-a-uuid\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Deposit {

        @Test
        void returns200WithTransactionDetails() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID depositId = UUID.randomUUID();

            mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("depositId", depositId, "amount", 100.00))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(depositId.toString()))
                    .andExpect(jsonPath("$.type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.amount").value(100.0))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        void idempotentDepositReturnsSameTransaction() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID depositId = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of("depositId", depositId, "amount", 100.00));

            String first  = mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(first).isEqualTo(second);
        }

        @Test
        void returns404WhenAccountNotFound() throws Exception {
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("depositId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenAmountMissing() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("depositId", UUID.randomUUID()))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenDepositIdMissing() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("amount", 100.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenAmountIsZero() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("depositId", UUID.randomUUID(), "amount", 0.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenAmountIsNegative() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("depositId", UUID.randomUUID(), "amount", -50.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenBodyIsEmpty() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void returns400WhenAccountIdIsInvalidFormat() throws Exception {
            mockMvc.perform(post("/accounts/current/{accountId}/deposit", "not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("depositId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void depositAppearsInTransactionHistory() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID depositId = UUID.randomUUID();
            deposit(accountId, depositId, 75.00);

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1))
                    .andExpect(jsonPath("$.transactions[0].transactionId").value(depositId.toString()))
                    .andExpect(jsonPath("$.transactions[0].type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.transactions[0].amount").value(75.0));
        }

        @Test
        void multipleDepositsAllAppearInHistory() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 10.00);
            deposit(accountId, UUID.randomUUID(), 20.00);
            deposit(accountId, UUID.randomUUID(), 30.00);

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(3));
        }
    }

    @Nested
    class Withdraw {

        @Test
        void returns200WithTransactionDetails() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);
            UUID withdrawId = UUID.randomUUID();

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", withdrawId, "amount", 50.00))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(withdrawId.toString()))
                    .andExpect(jsonPath("$.type").value("WITHDRAW"))
                    .andExpect(jsonPath("$.amount").value(50.0))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        void idempotentWithdrawReturnsSameTransaction() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);
            String body = objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 50.00));

            String first  = mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(first).isEqualTo(second);
        }

        @Test
        void returns422WhenInsufficientBalance() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 200.00))))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns404WhenAccountNotFound() throws Exception {
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 50.00))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenWithdrawIdMissing() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("amount", 50.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenWithdrawAmountMissing() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenWithdrawAmountIsZero() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenWithdrawAmountIsNegative() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", -10.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void idempotentWithdrawDoesNotDoubleDebit() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);
            UUID withdrawId = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of("withdrawId", withdrawId, "amount", 100.00));

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            // balance is 100 (debited once), not 0 — withdraw 100 should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));
        }

        @Test
        void withdrawAppearsInTransactionHistory() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);
            UUID withdrawId = UUID.randomUUID();

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", withdrawId, "amount", 50.00))))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(2))
                    .andExpect(jsonPath("$.transactions[?(@.transactionId == '" + withdrawId + "')].type").value("WITHDRAW"))
                    .andExpect(jsonPath("$.transactions[?(@.transactionId == '" + withdrawId + "')].amount").value(50.0));
        }

        @Test
        void canWithdrawExactBalance() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 150.00);

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 150.00))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("WITHDRAW"))
                    .andExpect(jsonPath("$.amount").value(150.0));

            // account now empty — any further withdrawal should fail
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));
        }
    }

    @Nested
    class Transfer {

        @Test
        void returns200WithTransferOutTransaction() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 500.00);
            UUID transferId = UUID.randomUUID();

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", transferId,
                                    "targetAccountId", targetAccountId,
                                    "amount", 200.00))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(transferId.toString()))
                    .andExpect(jsonPath("$.type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$.amount").value(200.0))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        void idempotentTransferReturnsSameTransaction() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 500.00);
            String body = objectMapper.writeValueAsString(Map.of(
                    "transferId", UUID.randomUUID(), "targetAccountId", targetAccountId, "amount", 100.00));

            String first  = mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String second = mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

            assertThat(first).isEqualTo(second);
        }

        @Test
        void returns422WhenInsufficientBalance() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 100.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", 200.00))))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns404WhenSourceNotFound() throws Exception {
            UUID targetAccountId = openAccount(UUID.randomUUID());

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", 100.00))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns404WhenTargetNotFound() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 500.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", UUID.randomUUID(),
                                    "amount", 100.00))))
                    .andExpect(status().isNotFound());
        }

        @Test
        void returns400WhenSourceAndTargetAreSameAccount() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", accountId,
                                    "amount", 100.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void selfTransferDoesNotAffectBalance() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);

            // attempt self-transfer — rejected with 400
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", accountId,
                                    "amount", 100.00))))
                    .andExpect(status().isBadRequest());

            // balance is unchanged — full 200 is still available
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 200.00))))
                    .andExpect(status().isOk());
        }

        @Test
        void selfTransferNotRecordedInHistory() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);

            // attempt self-transfer — rejected with 400
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", accountId,
                                    "amount", 50.00))))
                    .andExpect(status().isBadRequest());

            // only the deposit is in history — no transfer records
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1));
        }

        @Test
        void returns400ForSelfTransferEvenWhenAccountDoesNotExist() throws Exception {
            UUID nonExistentId = UUID.randomUUID();

            // self-transfer check fires before account existence check — expect 400, not 404
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", nonExistentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", nonExistentId,
                                    "amount", 100.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenTransferIdMissing() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 200.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "targetAccountId", targetAccountId,
                                    "amount", 100.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenTargetAccountIdMissing() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 200.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "amount", 100.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenTransferAmountMissing() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 200.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenTransferAmountIsZero() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 200.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", 0.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void canTransferExactBalance() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 250.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", 250.00))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$.amount").value(250.0));

            // source is now empty — further transfer should fail with 422
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", 0.01))))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenTransferAmountIsNegative() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 200.00);

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", -100.00))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void failedTransferLeavesBalancesUnchanged() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 50.00);

            // attempt to transfer more than the balance
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(),
                                    "targetAccountId", targetAccountId,
                                    "amount", 100.00))))
                    .andExpect(status().is(422));

            // source balance unchanged — can still withdraw 50
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 50.00))))
                    .andExpect(status().isOk());

            // target received nothing — any withdrawal should fail
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", targetAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));
        }

        @Test
        void transferAppearsInBothAccountHistories() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 300.00);
            UUID transferId = UUID.randomUUID();

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", transferId,
                                    "targetAccountId", targetAccountId,
                                    "amount", 150.00))))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", sourceAccountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions[?(@.transactionId == '" + transferId + "')].type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$.transactions[?(@.transactionId == '" + transferId + "')].amount").value(150.0));

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", targetAccountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions[?(@.transactionId == '" + transferId + "')].type").value("TRANSFER_IN"))
                    .andExpect(jsonPath("$.transactions[?(@.transactionId == '" + transferId + "')].amount").value(150.0));
        }

        @Test
        void idempotentTransferDoesNotDoubleDebit() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 200.00);
            UUID transferId = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of(
                    "transferId", transferId,
                    "targetAccountId", targetAccountId,
                    "amount", 100.00));

            // send twice with the same transferId
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            // source balance is 100 (debited once), not 0 — can still withdraw 100
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isOk());

            // source now empty
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));
        }
    }

    @Nested
    class GetTransactions {

        @Test
        void returns200WithEmptyListForNewAccount() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions").isArray())
                    .andExpect(jsonPath("$.transactions.length()").value(0));
        }

        @Test
        void returns200WithDepositTransaction() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID depositId = UUID.randomUUID();
            deposit(accountId, depositId, 100.00);

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1))
                    .andExpect(jsonPath("$.transactions[0].transactionId").value(depositId.toString()))
                    .andExpect(jsonPath("$.transactions[0].type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.transactions[0].amount").value(100.0))
                    .andExpect(jsonPath("$.transactions[0].timestamp").isNotEmpty());
        }

        @Test
        void returnsAllTransactionTypes() throws Exception {
            UUID sourceId = openAccount(UUID.randomUUID());
            UUID targetId = openAccount(UUID.randomUUID());
            UUID depositId  = UUID.randomUUID();
            UUID withdrawId = UUID.randomUUID();
            UUID transferId = UUID.randomUUID();

            deposit(sourceId, depositId, 500.00);

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", sourceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", withdrawId, "amount", 100.00))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", transferId, "targetAccountId", targetId, "amount", 200.00))))
                    .andExpect(status().isOk());

            // source has 3 transactions: deposit, withdraw, transfer_out
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", sourceId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(3));

            // target has 1 transaction: transfer_in
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", targetId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1))
                    .andExpect(jsonPath("$.transactions[0].transactionId").value(transferId.toString()))
                    .andExpect(jsonPath("$.transactions[0].type").value("TRANSFER_IN"))
                    .andExpect(jsonPath("$.transactions[0].amount").value(200.0));
        }

        @Test
        void idempotentDepositAppearsOnce() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID depositId = UUID.randomUUID();

            deposit(accountId, depositId, 100.00);
            deposit(accountId, depositId, 100.00);

            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1));
        }

        @Test
        void returns404WhenAccountNotFound() throws Exception {
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", UUID.randomUUID()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }

        @Test
        void returns400WhenAccountIdIsInvalidFormat() throws Exception {
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", "not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void idempotentWithdrawAppearsOnce() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 200.00);
            UUID withdrawId = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of("withdrawId", withdrawId, "amount", 50.00));

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            // deposit + withdraw = 2 entries, not 3
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(2));
        }

        @Test
        void idempotentTransferAppearsOnceInBothAccounts() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 300.00);
            UUID transferId = UUID.randomUUID();
            String body = objectMapper.writeValueAsString(Map.of(
                    "transferId", transferId, "targetAccountId", targetAccountId, "amount", 100.00));

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            // source: deposit + transfer_out = 2
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", sourceAccountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(2));

            // target: transfer_in = 1 (not 2)
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", targetAccountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1));
        }

        @Test
        void failedOperationsNotRecordedInHistory() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 100.00);

            // failed withdraw (insufficient balance)
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 999.00))))
                    .andExpect(status().is(422));

            // only the deposit is recorded
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1));
        }
    }

    @Nested
    class FullFlow {

        @Test
        void depositAndWithdraw() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            deposit(accountId, UUID.randomUUID(), 300.00);
            deposit(accountId, UUID.randomUUID(), 200.00);

            // balance = 500; withdraw 400 should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 400.00))))
                    .andExpect(status().isOk());

            // balance = 100; withdraw 200 should fail
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 200.00))))
                    .andExpect(status().is(422));
        }

        @Test
        void idempotentOperationsDoNotAffectBalance() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID depositId = UUID.randomUUID();

            // deposit 100 twice with the same depositId — balance should be 100, not 200
            deposit(accountId, depositId, 100.00);
            deposit(accountId, depositId, 100.00);

            // withdraw 100 should succeed (balance = 100)
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isOk());

            // account is now empty
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));
        }

        @Test
        void chainOfTransfers() throws Exception {
            UUID accountA = openAccount(UUID.randomUUID());
            UUID accountB = openAccount(UUID.randomUUID());
            UUID accountC = openAccount(UUID.randomUUID());
            deposit(accountA, UUID.randomUUID(), 300.00);

            // A → B: 300
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", accountA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(), "targetAccountId", accountB, "amount", 300.00))))
                    .andExpect(status().isOk());

            // B → C: 200
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", accountB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(), "targetAccountId", accountC, "amount", 200.00))))
                    .andExpect(status().isOk());

            // A has 0 — withdraw should fail
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));

            // B has 100 — withdraw 100 should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isOk());

            // C has 200 — withdraw 200 should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountC)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 200.00))))
                    .andExpect(status().isOk());
        }

        @Test
        void separateAccountsDoNotInterfereFunds() throws Exception {
            UUID accountA = openAccount(UUID.randomUUID());
            UUID accountB = openAccount(UUID.randomUUID());
            deposit(accountA, UUID.randomUUID(), 100.00);
            deposit(accountB, UUID.randomUUID(), 200.00);

            // drain account B entirely
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 200.00))))
                    .andExpect(status().isOk());

            // account A's balance is unaffected — withdraw 100 should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isOk());
        }

        @Test
        void transactionHistoryReflectsCompleteLifecycle() throws Exception {
            UUID accountId = openAccount(UUID.randomUUID());
            UUID targetId  = openAccount(UUID.randomUUID());

            deposit(accountId, UUID.randomUUID(), 500.00);
            deposit(accountId, UUID.randomUUID(), 300.00);

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 100.00))))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/accounts/current/{accountId}/transfer", accountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(), "targetAccountId", targetId, "amount", 200.00))))
                    .andExpect(status().isOk());

            // 2 deposits + 1 withdraw + 1 transfer_out = 4
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", accountId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(4));

            // 1 transfer_in on target
            mockMvc.perform(get("/accounts/current/{accountId}/transactions", targetId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1));
        }

        @Test
        void transferAndTargetSpendsFunds() throws Exception {
            UUID sourceAccountId = openAccount(UUID.randomUUID());
            UUID targetAccountId = openAccount(UUID.randomUUID());
            deposit(sourceAccountId, UUID.randomUUID(), 500.00);

            // transfer 300 from source to target
            mockMvc.perform(post("/accounts/current/{accountId}/transfer", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "transferId", UUID.randomUUID(), "targetAccountId", targetAccountId, "amount", 300.00))))
                    .andExpect(status().isOk());

            // target withdraws 300 — should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", targetAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 300.00))))
                    .andExpect(status().isOk());

            // source withdraws remaining 200 — should succeed
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 200.00))))
                    .andExpect(status().isOk());

            // both accounts empty — further withdrawals should fail
            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", sourceAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));

            mockMvc.perform(post("/accounts/current/{accountId}/withdraw", targetAccountId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("withdrawId", UUID.randomUUID(), "amount", 0.01))))
                    .andExpect(status().is(422));
        }
    }
}
