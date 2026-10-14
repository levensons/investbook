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
import ru.investbook.entity.ForeignExchangeRateEntity;
import ru.investbook.repository.ForeignExchangeRateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForeignExchangeRateServiceTest {
    private final ForeignExchangeRateRepository repository = mock(ForeignExchangeRateRepository.class);
    private final ForeignExchangeRateService service = new ForeignExchangeRateService(repository);
    private final LocalDate date = LocalDate.of(2025, 1, 6);

    @Test
    void clearsBothLatestAndHistoricalRatesAfterCorrection() {
        when(repository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", date)).thenReturn(Optional.of(rate("80")));
        when(repository.findFirstByPkCurrencyPairOrderByPkDateDesc("USDRUB")).thenReturn(Optional.of(rate("100")));
        assertEquals(new BigDecimal("80"), service.getExchangeRate("USD", "RUB", date));
        assertEquals(new BigDecimal("100"), service.getExchangeRate("USD", "RUB"));
        when(repository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", date)).thenReturn(Optional.of(rate("90")));
        when(repository.findFirstByPkCurrencyPairOrderByPkDateDesc("USDRUB")).thenReturn(Optional.of(rate("110")));
        service.invalidateCache();
        assertEquals(new BigDecimal("90"), service.getExchangeRate("USD", "RUB", date));
        assertEquals(new BigDecimal("110"), service.getExchangeRate("USD", "RUB"));
    }

    @Test
    void doesNotSubstituteLatestRateForMissingHistoricalRate() {
        when(repository.findFirstByPkCurrencyPairOrderByPkDateDesc("USDRUB")).thenReturn(Optional.of(rate("100")));
        assertThrows(NoSuchElementException.class, () -> service.getExchangeRate("USD", "RUB", date));
    }

    @Test
    void recognizesRubleAliasesForHistoricalCrossRates() {
        when(repository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", date)).thenReturn(Optional.of(rate("80")));
        assertEquals(0, new BigDecimal("0.0125").compareTo(service.getExchangeRate("rur", "usd", date)));
        assertEquals(BigDecimal.ONE, service.getExchangeRate("SUR", "RUB", date));
    }

    private static ForeignExchangeRateEntity rate(String value) {
        ForeignExchangeRateEntity entity = new ForeignExchangeRateEntity();
        entity.setRate(new BigDecimal(value));
        return entity;
    }
}
