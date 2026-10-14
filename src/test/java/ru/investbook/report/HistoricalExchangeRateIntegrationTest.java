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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.investbook.entity.ForeignExchangeRateEntity;
import ru.investbook.entity.ForeignExchangeRateEntityPk;
import ru.investbook.repository.ForeignExchangeRateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class HistoricalExchangeRateIntegrationTest {
    @Autowired
    private ForeignExchangeRateRepository repository;

    @Test
    void selectsTheEffectiveHistoricalRateIncludingWeekendsButNeverAFutureRate() {
        // Synthetic currency isolates this test from any seeded exchange rates.
        save(LocalDate.of(2025, 1, 10), "80");
        save(LocalDate.of(2025, 1, 13), "90");
        save(LocalDate.of(2025, 1, 20), "200");
        repository.flush();
        ForeignExchangeRateService service = new ForeignExchangeRateService(repository);
        assertEquals(80, service.getExchangeRate("ZZZ", "RUB", LocalDate.of(2025, 1, 10)).intValueExact());
        assertEquals(80, service.getExchangeRate("ZZZ", "RUB", LocalDate.of(2025, 1, 12)).intValueExact());
        assertEquals(90, service.getExchangeRate("ZZZ", "RUB", LocalDate.of(2025, 1, 13)).intValueExact());
        assertThrows(NoSuchElementException.class,
                () -> service.getExchangeRate("ZZZ", "RUB", LocalDate.of(2025, 1, 9)));
    }

    private void save(LocalDate date, String value) {
        ForeignExchangeRateEntityPk pk = new ForeignExchangeRateEntityPk();
        pk.setCurrencyPair("ZZZRUB");
        pk.setDate(date);
        ForeignExchangeRateEntity entity = new ForeignExchangeRateEntity();
        entity.setPk(pk);
        entity.setRate(new BigDecimal(value));
        repository.save(entity);
    }
}
