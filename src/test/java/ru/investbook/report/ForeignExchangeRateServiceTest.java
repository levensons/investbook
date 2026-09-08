/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.investbook.entity.ForeignExchangeRateEntity;
import ru.investbook.repository.ForeignExchangeRateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForeignExchangeRateServiceTest {

    @Mock
    private ForeignExchangeRateRepository repository;

    @Test
    void usesLastKnownRateForNonTradingDate() {
        LocalDate quoteDate = LocalDate.of(2026, 8, 4);
        ForeignExchangeRateEntity entity = new ForeignExchangeRateEntity();
        entity.setRate(new BigDecimal("79.357"));
        when(repository.findFirstByPkCurrencyPairAndPkDateLessThanEqualOrderByPkDateDesc("USDRUB", quoteDate))
                .thenReturn(Optional.of(entity));

        ForeignExchangeRateService service = new ForeignExchangeRateService(repository);

        assertEquals(
                0,
                service.getExchangeRate("USD", "RUB", quoteDate).compareTo(new BigDecimal("79.357")));
    }
}
