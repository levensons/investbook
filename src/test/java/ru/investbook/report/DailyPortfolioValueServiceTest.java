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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DailyPortfolioValueServiceTest {

    private static final DailyPortfolioValueService.AccountCurrency ACCOUNT_RUB =
            new DailyPortfolioValueService.AccountCurrency("account", "RUB");

    @Test
    void infersOpeningCashFromFirstBrokerSnapshot() {
        Map<LocalDate, Map<DailyPortfolioValueService.AccountCurrency, BigDecimal>> snapshots = new TreeMap<>();
        snapshots.put(
                LocalDate.of(2025, 7, 27),
                Map.of(ACCOUNT_RUB, new BigDecimal("158.46")));
        Map<LocalDate, Map<DailyPortfolioValueService.AccountCurrency, BigDecimal>> transactionChanges = Map.of(
                LocalDate.of(2025, 2, 12),
                Map.of(ACCOUNT_RUB, new BigDecimal("-451961.54")));
        Map<LocalDate, Map<DailyPortfolioValueService.AccountCurrency, BigDecimal>> eventChanges = Map.of(
                LocalDate.of(2025, 4, 24),
                Map.of(ACCOUNT_RUB, new BigDecimal("52000.00")));

        Map<DailyPortfolioValueService.AccountCurrency, BigDecimal> result =
                DailyPortfolioValueService.inferInitialCash(
                        snapshots,
                        transactionChanges,
                        eventChanges);

        assertEquals(new BigDecimal("400120.00"), result.get(ACCOUNT_RUB));
    }

    @Test
    void usesFirstSnapshotAndIgnoresLaterChanges() {
        Map<LocalDate, Map<DailyPortfolioValueService.AccountCurrency, BigDecimal>> snapshots = new TreeMap<>();
        snapshots.put(
                LocalDate.of(2025, 7, 27),
                Map.of(ACCOUNT_RUB, new BigDecimal("100.00")));
        snapshots.put(
                LocalDate.of(2026, 7, 27),
                Map.of(ACCOUNT_RUB, new BigDecimal("300.00")));
        Map<LocalDate, Map<DailyPortfolioValueService.AccountCurrency, BigDecimal>> transactionChanges = new TreeMap<>();
        transactionChanges.put(
                LocalDate.of(2025, 2, 12),
                Map.of(ACCOUNT_RUB, new BigDecimal("-50.00")));
        transactionChanges.put(
                LocalDate.of(2026, 2, 12),
                Map.of(ACCOUNT_RUB, new BigDecimal("-200.00")));

        Map<DailyPortfolioValueService.AccountCurrency, BigDecimal> result =
                DailyPortfolioValueService.inferInitialCash(
                        snapshots,
                        transactionChanges,
                        Map.of());

        assertEquals(new BigDecimal("150.00"), result.get(ACCOUNT_RUB));
    }
}
