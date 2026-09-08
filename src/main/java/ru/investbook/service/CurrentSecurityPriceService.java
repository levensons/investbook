/*
 * InvestBook
 * Copyright (C) 2026  Spacious Team <spacious-team@ya.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package ru.investbook.service;

import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spacious_team.broker.pojo.Security;
import org.spacious_team.broker.pojo.SecurityQuote;
import org.springframework.stereotype.Service;
import ru.investbook.report.ForeignExchangeRateService;
import ru.investbook.service.moex.MoexIssSecurityQuoteService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.spacious_team.broker.pojo.SecurityType.DERIVATIVE;
import static ru.investbook.report.ForeignExchangeRateService.RUB;

@Service
@RequiredArgsConstructor
public class CurrentSecurityPriceService {

    private final MoexIssSecurityQuoteService moexQuoteService;
    private final SecurityProfitService securityProfitService;
    private final ForeignExchangeRateService foreignExchangeRateService;

    public Optional<BigDecimal> getDirtyPriceInRub(Security security) {
        return Optional.ofNullable(security.getId())
                .flatMap(securityId -> moexQuoteService.getCurrentQuote(securityId)
                        .flatMap(quote -> getDirtyPriceInRub(security, quote))
                        .or(() -> Optional.ofNullable(securityProfitService.getSecurityQuote(
                                        security,
                                        RUB,
                                        Instant.now()))
                                .flatMap(quote -> getDirtyPriceInRub(security, quote)))
                        .or(() -> securityProfitService.getSecurityQuoteFromLastTransaction(security, RUB)));
    }

    private Optional<BigDecimal> getDirtyPriceInRub(Security security, SecurityQuote quote) {
        try {
            @Nullable BigDecimal dirtyPrice = quote.getDirtyPriceInCurrency(security.getType() == DERIVATIVE);
            if (dirtyPrice == null) {
                return Optional.empty();
            }
            String currency = Optional.ofNullable(quote.getCurrency()).orElse(RUB);
            return Optional.of(foreignExchangeRateService.convertValueToCurrency(dirtyPrice, currency, RUB));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
