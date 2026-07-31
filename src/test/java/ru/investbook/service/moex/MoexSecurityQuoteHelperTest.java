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
import org.spacious_team.broker.pojo.SecurityQuote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.util.Objects.requireNonNull;

class MoexSecurityQuoteHelperTest {

    @Test
    void parsesStockHistory() {
        SecurityQuote quote = MoexSecurityQuoteHelper.parseHistory(Map.of(
                        "TRADEDATE", "2026-07-29",
                        "CLOSE", 275.7,
                        "CURRENCYID", "SUR"), "RUB")
                .orElseThrow()
                .build();

        assertEquals(LocalDate.of(2026, 7, 29),
                LocalDate.ofInstant(quote.getTimestamp(), ZoneId.systemDefault()));
        assertEquals(0, quote.getQuote().compareTo(new BigDecimal("275.7")));
        assertEquals("RUB", quote.getCurrency());
        assertNull(quote.getPrice());
        assertNull(quote.getAccruedInterest());
    }

    @Test
    void parsesBondDirtyPriceComponents() {
        SecurityQuote quote = MoexSecurityQuoteHelper.parseHistory(Map.of(
                        "TRADEDATE", "2026-07-29",
                        "LEGALCLOSEPRICE", 100.1252,
                        "FACEVALUE", 1000,
                        "ACCINT", 217.2065,
                        "CURRENCYID", "SUR"), "RUB")
                .orElseThrow()
                .build();

        assertEquals(0, quote.getQuote().compareTo(new BigDecimal("100.1252")));
        assertEquals(0, requireNonNull(quote.getPrice()).compareTo(new BigDecimal("1001.2520")));
        assertEquals(0, requireNonNull(quote.getAccruedInterest()).compareTo(new BigDecimal("217.2065")));
        assertEquals("RUB", quote.getCurrency());
    }

    @Test
    void ignoresHistoryRowWithoutPrice() {
        assertTrue(MoexSecurityQuoteHelper.parseHistory(Map.of(
                "TRADEDATE", "2026-07-29",
                "CURRENCYID", "SUR"), "RUB").isEmpty());
    }

    @Test
    void convertsForeignCurrencyBondCleanPriceToTradingCurrency() {
        Map<String, Object> row = Map.of(
                "TRADEDATE", "2026-07-30",
                "LEGALCLOSEPRICE", 99.2341,
                "FACEVALUE", 1000,
                "FACEUNIT", "USD",
                "ACCINT", 305.054886,
                "CURRENCYID", "SUR");
        SecurityQuote parsed = MoexSecurityQuoteHelper.parseHistory(row, "RUB")
                .orElseThrow()
                .build();

        SecurityQuote normalized = MoexSecurityQuoteHelper.normalizeBondPriceCurrency(
                parsed,
                row,
                "RUB",
                (base, quote, date) -> new BigDecimal("80"));

        assertEquals("RUB", normalized.getCurrency());
        assertEquals(0, requireNonNull(normalized.getPrice()).compareTo(new BigDecimal("79387.28000")));
        assertEquals(0, requireNonNull(normalized.getAccruedInterest()).compareTo(new BigDecimal("305.054886")));
    }
}
