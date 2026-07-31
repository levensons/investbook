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

package ru.investbook.service.moex;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoexIndexQuoteTest {

    @Test
    void parsesIndexHistoryRow() {
        MoexIndexQuote quote = MoexIssClientImpl.parseIndexQuote(Map.of(
                        "TRADEDATE", "2026-07-29",
                        "CLOSE", 2237.17))
                .orElseThrow();

        assertEquals(LocalDate.of(2026, 7, 29), quote.date());
        assertEquals(new BigDecimal("2237.17"), quote.close());
    }

    @Test
    void skipsRowWithoutClose() {
        assertTrue(MoexIssClientImpl.parseIndexQuote(Map.of(
                        "TRADEDATE", "2026-07-29"))
                .isEmpty());
    }
}
