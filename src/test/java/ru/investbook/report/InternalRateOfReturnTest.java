/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.investbook.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spacious_team.broker.pojo.CashFlowType;
import org.spacious_team.broker.pojo.Security;
import org.spacious_team.broker.pojo.SecurityQuote;
import org.spacious_team.broker.pojo.SecurityType;
import org.spacious_team.broker.pojo.Transaction;
import ru.investbook.entity.ForeignExchangeRateEntity;
import ru.investbook.entity.SecurityEventCashFlowEntity;
import ru.investbook.entity.TransactionCashFlowEntity;
import ru.investbook.repository.ForeignExchangeRateRepository;
import ru.investbook.repository.SecurityEventCashFlowRepository;
import ru.investbook.repository.TransactionCashFlowRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalRateOfReturnTest {
    private static final LocalDate OPEN = LocalDate.of(2025, 1, 6);
    private static final LocalDate CLOSE = OPEN.plusDays(365);
    private static final Instant FROM = at(OPEN).minusSeconds(1);
    private static final Instant TO = at(CLOSE).plusSeconds(1);
    private static final Set<String> ACCOUNTS = Set.of("test-account");
    private final Security security = Security.builder().id(1).type(SecurityType.STOCK).build();
    private final FifoPositionsFactory factory = mock(FifoPositionsFactory.class);
    private final TransactionCashFlowRepository transactionCash = mock(TransactionCashFlowRepository.class);
    private final SecurityEventCashFlowRepository payments = mock(SecurityEventCashFlowRepository.class);
    private final ForeignExchangeRateRepository rates = mock(ForeignExchangeRateRepository.class);
    private final InternalRateOfReturn service = new InternalRateOfReturn(factory, transactionCash, payments,
            new ForeignExchangeRateService(rates));

    @AfterEach
    void clearThreadContext() {
        ViewFilter.remove();
    }

    @Test
    void calculatesSingleCurrencyReturnWithoutThreadLocalFilter() {
        positions("-1000", "RUB", "1100", "RUB", true);
        assertReturn(0.1, null);
    }

    @Test
    void convertsDividendAtItsPaymentDate() {
        positions("-100", "USD", "100", "USD", true);
        dividend();
        historicalRates();
        assertReturn(0.08, null); // 800 RUB / 100 RUB per USD = 8 USD
    }

    @Test
    void usesExplicitPeriodEvenWhenThreadLocalFilterDisagrees() {
        positions("-100", "USD", "100", "USD", true);
        dividend();
        historicalRates();
        ViewFilter.set(ViewFilter.builder().fromDate(FROM).toDate(at(CLOSE.minusDays(1))).build());
        assertReturn(0.08, null);
        verify(payments).findByAccountIdInAndSecurityIdAndCashFlowTypeIdInAndTimestampBetweenOrderByTimestampAsc(
                eq(ACCOUNTS), eq(1), any(), eq(FROM), eq(TO));
    }

    @Test
    void convertsSaleProceedsAtTheTransactionDate() {
        positions("-100", "USD", "11000", "RUB", true);
        historicalRates();
        assertReturn(0.1, null);
    }

    @Test
    void convertsTheTerminalValuationAtTheQuoteDate() {
        positions("-100", "USD", "0", "USD", false);
        historicalRates();
        SecurityQuote quote = SecurityQuote.builder().security(1).currency("RUB")
                .quote(new BigDecimal("11000")).timestamp(at(CLOSE)).build();
        assertReturn(0.1, quote);
    }

    @Test
    void reportsUnavailableReturnWhenHistoricalFxIsMissing() {
        positions("-100", "USD", "100", "USD", true);
        dividend();
        when(rates.findFirstByPkCurrencyPairOrderByPkDateDesc("USDRUB"))
                .thenReturn(Optional.of(rate("200")));
        assertNull(service.calc(ACCOUNTS, security, null, FROM, TO));
    }

    @Test
    void requiresAQuoteForAnOpenPosition() {
        positions("-100", "USD", "0", "USD", false);
        assertNull(service.calc(ACCOUNTS, security, null, FROM, TO));
    }

    private void assertReturn(double expected, SecurityQuote quote) {
        Double result = service.calc(ACCOUNTS, security, quote, FROM, TO);
        assertNotNull(result);
        assertEquals(expected, result, 1e-7);
    }

    private void positions(String purchase, String purchaseCurrency, String sale, String saleCurrency, boolean closed) {
        Transaction buy = Transaction.builder().id(1).account("test-account").security(1).count(1)
                .timestamp(at(OPEN)).build();
        Transaction sell = Transaction.builder().id(2).account("test-account").security(1).count(-1)
                .timestamp(at(CLOSE)).build();
        FifoPositions positions = new FifoPositions(new ArrayDeque<>(closed ? List.of(buy, sell) : List.of(buy)),
                new ArrayDeque<>());
        when(factory.get(eq(security), any(FifoPositionsFilter.class))).thenReturn(positions);
        TransactionCashFlowEntity buyCash = cash(purchase, purchaseCurrency);
        TransactionCashFlowEntity sellCash = cash(sale, saleCurrency);
        when(transactionCash.findByTransactionId(1)).thenReturn(List.of(buyCash));
        when(transactionCash.findByTransactionId(2)).thenReturn(List.of(sellCash));
        when(transactionCash.findByTransactionIdAndCashFlowType(1, CashFlowType.PRICE)).thenReturn(Optional.of(buyCash));
        when(transactionCash.findByTransactionIdAndCashFlowType(2, CashFlowType.PRICE)).thenReturn(Optional.of(sellCash));
    }

    private void dividend() {
        SecurityEventCashFlowEntity dividend = new SecurityEventCashFlowEntity();
        dividend.setValue(new BigDecimal("800"));
        dividend.setCurrency("RUB");
        dividend.setTimestamp(at(CLOSE));
        when(payments.findByAccountIdInAndSecurityIdAndCashFlowTypeIdInAndTimestampBetweenOrderByTimestampAsc(
                any(), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
                    Instant from = invocation.getArgument(3);
                    Instant to = invocation.getArgument(4);
                    return !dividend.getTimestamp().isBefore(from) && !dividend.getTimestamp().isAfter(to) ?
                            List.of(dividend) : List.of();
                });
    }

    private void historicalRates() {
        when(rates.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", CLOSE)).thenReturn(Optional.of(rate("100")));
        when(rates.findFirstByPkCurrencyPairOrderByPkDateDesc("USDRUB"))
                .thenReturn(Optional.of(rate("200")));
    }

    private static Instant at(LocalDate date) {
        return date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    private static TransactionCashFlowEntity cash(String value, String currency) {
        TransactionCashFlowEntity c = new TransactionCashFlowEntity();
        c.setValue(new BigDecimal(value));
        c.setCurrency(currency);
        return c;
    }

    private static ForeignExchangeRateEntity rate(String value) {
        ForeignExchangeRateEntity r = new ForeignExchangeRateEntity();
        r.setRate(new BigDecimal(value));
        return r;
    }
}
