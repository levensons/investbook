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

import org.junit.jupiter.api.Test;
import org.spacious_team.broker.pojo.AccountPropertyType;
import org.spacious_team.broker.pojo.CashFlowType;
import ru.investbook.entity.AccountCashEntity;
import ru.investbook.entity.AccountEntity;
import ru.investbook.entity.AccountPropertyEntity;
import ru.investbook.entity.CashFlowTypeEntity;
import ru.investbook.entity.EventCashFlowEntity;
import ru.investbook.entity.ForeignExchangeRateEntity;
import ru.investbook.entity.SecurityEntity;
import ru.investbook.entity.SecurityQuoteEntity;
import ru.investbook.entity.TransactionCashFlowEntity;
import ru.investbook.entity.TransactionEntity;
import ru.investbook.repository.AccountCashRepository;
import ru.investbook.repository.AccountPropertyRepository;
import ru.investbook.repository.EventCashFlowRepository;
import ru.investbook.repository.ForeignExchangeRateRepository;
import ru.investbook.repository.SecurityQuoteRepository;
import ru.investbook.repository.SecurityRepository;
import ru.investbook.repository.TransactionCashFlowRepository;
import ru.investbook.repository.TransactionRepository;
import ru.investbook.service.moex.MoexHistoricalQuoteService;
import ru.investbook.web.model.DailyPortfolioValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioReturnRegressionTest {
    private static final LocalDate FIRST = LocalDate.of(2025, 1, 6);
    private static final LocalDate SECOND = FIRST.plusDays(1);
    private static final LocalDate THIRD = FIRST.plusDays(2);

    @Test
    void measuresPriceGrowthWithoutCashFlows() {
        Fixture f = rubPortfolio("110");
        assertEquals(10, f.run(SECOND).getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void includesDepositInCapitalBeforeTheDailyPriceRise() {
        Fixture f = rubPortfolio("110");
        f.deposit(SECOND, "1000", "RUB");
        f.trade(SECOND, 10, "-1000", "RUB");
        DailyPortfolioValue last = f.run(SECOND).getLast();
        assertEquals(2200, last.assetsRub(), 1e-8);
        assertEquals(10, last.assetsGrowth(), 1e-8); // 200 profit on 2000 invested
    }

    @Test
    void doesNotCountAnUninvestedDepositAsProfit() {
        Fixture f = rubPortfolio("100");
        f.deposit(SECOND, "1000", "RUB");
        assertEquals(0, f.run(SECOND).getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void addsBackAnEndOfDayWithdrawalWithoutRemovingItsReturn() {
        Fixture f = rubPortfolio("110");
        f.trade(SECOND, -5, "550", "RUB");
        f.deposit(SECOND, "-550", "RUB");
        DailyPortfolioValue last = f.run(SECOND).getLast();
        assertEquals(550, last.assetsRub(), 1e-8);
        assertEquals(10, last.assetsGrowth(), 1e-8);
    }

    @Test
    void chainsReturnsAcrossDepositsAndWithdrawals() {
        Fixture f = rubPortfolio("110");
        f.deposit(SECOND, "1000", "RUB");
        f.trade(SECOND, 10, "-1000", "RUB");
        f.trade(THIRD, -10, "1210", "RUB");
        f.deposit(THIRD, "-1210", "RUB");
        f.quote(THIRD, "121", "RUB");
        assertEquals(21, f.run(THIRD).getLast().assetsGrowth(), 1e-8); // 1.1 * 1.1 - 1
    }

    @Test
    void preservesReturnAfterFullWithdrawalAndReinvestment() {
        Fixture f = rubPortfolio("110");
        f.trade(SECOND, -10, "1100", "RUB");
        f.deposit(SECOND, "-1100", "RUB");
        f.deposit(THIRD, "1000", "RUB");
        f.trade(THIRD, 10, "-1000", "RUB");
        f.quote(THIRD, "110", "RUB");
        List<DailyPortfolioValue> values = f.run(THIRD);
        assertEquals(0, values.get(1).assetsRub(), 1e-8);
        assertEquals(10, values.get(1).assetsGrowth(), 1e-8);
        assertEquals(21, values.getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void valuesForeignPositionsAtEachHistoricalRate() {
        Fixture f = usdPortfolio();
        List<DailyPortfolioValue> values = f.run(SECOND);
        assertEquals(8000, values.getFirst().assetsRub(), 1e-8);
        assertEquals(10000, values.getLast().assetsRub(), 1e-8);
        assertEquals(25, values.getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void convertsForeignDepositsAtTheirOwnDates() {
        Fixture f = usdPortfolio();
        f.deposit(SECOND, "100", "USD");
        List<DailyPortfolioValue> values = f.run(SECOND);
        assertEquals(20000, values.getLast().assetsRub(), 1e-8);
        assertEquals(100.0 * 2000 / 18000, values.getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void convertsCashAndBrokerSnapshotsAtTheirDates() {
        Fixture f = usdPortfolio();
        f.cashSnapshot(FIRST, "10", "USD");
        f.brokerSnapshot(FIRST, "110");
        List<DailyPortfolioValue> values = f.run(SECOND);
        assertEquals(8800, values.getFirst().assetsRub(), 1e-8);
        assertEquals(11000, values.getLast().assetsRub(), 1e-8);
        assertEquals(25, values.getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void excludesCashReconciliationFromProfitAtItsHistoricalRate() {
        Fixture f = usdPortfolio();
        f.cashSnapshot(FIRST, "0", "USD");
        f.cashSnapshot(SECOND, "10", "USD");
        List<DailyPortfolioValue> values = f.run(SECOND);
        assertEquals(11000, values.getLast().assetsRub(), 1e-8);
        assertEquals(25, values.getLast().assetsGrowth(), 1e-8);
    }

    @Test
    void neverSubstitutesLatestFxForMissingHistory() {
        Fixture f = usdPortfolio();
        when(f.fxRepository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", FIRST)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> f.run(SECOND));
        verify(f.fxRepository, never()).findFirstByPkCurrencyPairOrderByPkDateDesc(anyString());
    }

    private static Fixture rubPortfolio(String lastPrice) {
        Fixture f = new Fixture();
        f.deposit(FIRST, "1000", "RUB");
        f.trade(FIRST, 10, "-1000", "RUB");
        f.quote(FIRST, "100", "RUB");
        f.quote(SECOND, lastPrice, "RUB");
        return f;
    }

    private static Fixture usdPortfolio() {
        Fixture f = new Fixture();
        f.deposit(FIRST, "100", "USD");
        f.trade(FIRST, 1, "-100", "USD");
        f.quote(FIRST, "100", "USD");
        f.quote(SECOND, "100", "USD");
        return f;
    }

    private static Instant at(LocalDate day) {
        return day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
    }

    private static CashFlowTypeEntity type(CashFlowType value) {
        CashFlowTypeEntity type = new CashFlowTypeEntity();
        type.setId(value.getId());
        return type;
    }

    private static ForeignExchangeRateEntity rate(String value) {
        ForeignExchangeRateEntity rate = new ForeignExchangeRateEntity();
        rate.setRate(new BigDecimal(value));
        return rate;
    }

    private static class Fixture {
        final ForeignExchangeRateRepository fxRepository = mock(ForeignExchangeRateRepository.class);
        final AccountEntity account = new AccountEntity();
        final SecurityEntity security = new SecurityEntity();
        final List<TransactionEntity> trades = new ArrayList<>();
        final List<TransactionCashFlowEntity> tradeCash = new ArrayList<>();
        final List<EventCashFlowEntity> events = new ArrayList<>();
        final List<SecurityQuoteEntity> quotes = new ArrayList<>();
        final List<AccountCashEntity> cash = new ArrayList<>();
        final List<AccountPropertyEntity> brokerAssets = new ArrayList<>();
        final DailyPortfolioValueService service;

        Fixture() {
            account.setId("test-account");
            security.setId(1);
            TransactionRepository tr = mock(TransactionRepository.class);
            SecurityRepository sr = mock(SecurityRepository.class);
            SecurityQuoteRepository qr = mock(SecurityQuoteRepository.class);
            TransactionCashFlowRepository tc = mock(TransactionCashFlowRepository.class);
            AccountCashRepository ac = mock(AccountCashRepository.class);
            AccountPropertyRepository ap = mock(AccountPropertyRepository.class);
            EventCashFlowRepository ec = mock(EventCashFlowRepository.class);
            when(tr.findByAccountInAndTimestampLessThanOrderByTimestampAscTradeIdAsc(any(), any())).thenReturn(trades);
            when(sr.findAllById(any())).thenReturn(List.of(security));
            when(qr.findBySecurityIdInAndTimestampBetweenOrderByTimestampAsc(any(), any(), any())).thenReturn(quotes);
            when(tc.findByTransactionIdIn(any())).thenReturn(tradeCash);
            when(ac.findByAccountIn(any())).thenReturn(cash);
            when(ap.findByAccountIdInAndPropertyInAndTimestampBetweenOrderByTimestampAsc(any(), any(), any(), any()))
                    .thenReturn(brokerAssets);
            when(ec.findByAccountIdInAndTimestampBetweenOrderByTimestamp(any(), any(), any())).thenReturn(events);
            when(fxRepository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", FIRST)).thenReturn(Optional.of(rate("80")));
            when(fxRepository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", SECOND)).thenReturn(Optional.of(rate("100")));
            when(fxRepository.findFirstByPkCurrencyPairOrderByPkDateDesc("USDRUB"))
                    .thenReturn(Optional.of(rate("200"))); // deliberately different from both historical dates
            service = new DailyPortfolioValueService(tr, sr, qr, tc, ac, ap, ec,
                    new ForeignExchangeRateService(fxRepository), mock(MoexHistoricalQuoteService.class));
        }

        void trade(LocalDate date, int count, String value, String currency) {
            TransactionEntity t = new TransactionEntity();
            t.setId(trades.size() + 1);
            t.setTradeId("trade-" + t.getId());
            t.setAccount(account.getId());
            t.setSecurity(security);
            t.setTimestamp(at(date));
            t.setCount(count);
            trades.add(t);
            TransactionCashFlowEntity c = new TransactionCashFlowEntity();
            c.setTransactionId(t.getId());
            c.setCashFlowType(type(CashFlowType.PRICE));
            c.setCurrency(currency);
            c.setValue(new BigDecimal(value));
            tradeCash.add(c);
        }

        void deposit(LocalDate date, String value, String currency) {
            EventCashFlowEntity e = new EventCashFlowEntity();
            e.setAccount(account);
            e.setTimestamp(at(date));
            e.setCashFlowType(type(CashFlowType.CASH));
            e.setValue(new BigDecimal(value));
            e.setCurrency(currency);
            events.add(e);
        }

        void quote(LocalDate date, String value, String currency) {
            SecurityQuoteEntity q = new SecurityQuoteEntity();
            q.setSecurity(security);
            q.setTimestamp(at(date));
            q.setQuote(new BigDecimal(value));
            q.setCurrency(currency);
            quotes.add(q);
        }

        void cashSnapshot(LocalDate date, String value, String currency) {
            AccountCashEntity c = new AccountCashEntity();
            c.setAccount(account.getId());
            c.setTimestamp(at(date));
            c.setValue(new BigDecimal(value));
            c.setCurrency(currency);
            cash.add(c);
        }

        void brokerSnapshot(LocalDate date, String value) {
            AccountPropertyEntity p = new AccountPropertyEntity();
            p.setAccount(account);
            p.setTimestamp(at(date));
            p.setProperty(AccountPropertyType.TOTAL_ASSETS_USD.name());
            p.setValue(value);
            brokerAssets.add(p);
        }

        List<DailyPortfolioValue> run(LocalDate to) {
            return service.getDailyValues(ViewFilter.builder()
                    .accounts(Set.of(account.getId()))
                    .fromDate(FIRST.atStartOfDay(ZoneId.systemDefault()).toInstant())
                    .toDate(to.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant())
                    .build());
        }
    }
}
