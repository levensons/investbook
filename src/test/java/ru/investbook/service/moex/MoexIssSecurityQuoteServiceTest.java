/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.service.moex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.spacious_team.broker.pojo.SecurityQuote;
import ru.investbook.converter.SecurityQuoteConverter;
import ru.investbook.entity.SecurityEntity;
import ru.investbook.repository.SecurityQuoteRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.spacious_team.broker.pojo.SecurityType.STOCK;

@ExtendWith(MockitoExtension.class)
class MoexIssSecurityQuoteServiceTest {

    @Mock
    private MoexIssClient moexClient;
    @Mock
    private SecurityQuoteConverter securityQuoteConverter;
    @Mock
    private SecurityQuoteRepository securityQuoteRepository;

    @Test
    void keepsCurrentQuoteOutsideHistoricalRepository() {
        SecurityEntity security = new SecurityEntity();
        security.setId(42);
        security.setType(STOCK);
        security.setTicker("SBER");
        MoexMarketDescription market = new MoexMarketDescription("stock", "shares", "TQBR", "RUB");
        SecurityQuote currentQuote = SecurityQuote.builder()
                .timestamp(Instant.parse("2026-08-04T08:51:01Z"))
                .quote(new BigDecimal("282.35"))
                .currency("RUB")
                .build();
        when(moexClient.getSecId("SBER", STOCK)).thenReturn(Optional.of("SBER"));
        when(moexClient.getMarket("SBER")).thenReturn(Optional.of(market));
        when(moexClient.getQuote("SBER", market)).thenReturn(Optional.empty());
        when(moexClient.getCurrentQuote("SBER", market)).thenReturn(Optional.of(currentQuote));

        MoexIssSecurityQuoteService service = new MoexIssSecurityQuoteService(
                moexClient,
                securityQuoteConverter,
                securityQuoteRepository);
        service.updateQuote(security);

        SecurityQuote result = service.getCurrentQuote(42).orElseThrow();
        assertEquals(42, result.getSecurity());
        assertEquals(0, result.getQuote().compareTo(new BigDecimal("282.35")));
        assertEquals(currentQuote.getTimestamp(), service.getLastCurrentQuoteTimestamp().orElseThrow());
    }
}
