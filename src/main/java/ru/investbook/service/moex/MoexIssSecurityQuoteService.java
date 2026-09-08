/*
 * InvestBook
 * Copyright (C) 2022  Spacious Team <spacious-team@ya.ru>
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spacious_team.broker.pojo.SecurityQuote;
import org.spacious_team.broker.pojo.SecurityType;
import org.springframework.stereotype.Service;
import ru.investbook.converter.SecurityQuoteConverter;
import ru.investbook.entity.SecurityEntity;
import ru.investbook.repository.SecurityQuoteRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.spacious_team.broker.pojo.SecurityType.ASSET;
import static org.spacious_team.broker.pojo.SecurityType.CURRENCY_PAIR;
import static ru.investbook.repository.RepositoryHelper.isUniqIndexViolationException;

@Service
@Slf4j
@RequiredArgsConstructor
public class MoexIssSecurityQuoteService {

    private final MoexIssClient moexClient;
    private final SecurityQuoteConverter securityQuoteConverter;
    private final SecurityQuoteRepository securityQuoteRepository;
    private final Map<Integer, SecurityQuote> currentQuotes = new ConcurrentHashMap<>();

    public void updateQuote(SecurityEntity security) {
        try {
            Integer securityId = security.getId();
            SecurityType securityType = security.getType();
            if (securityType == CURRENCY_PAIR) {
                return; // currency pair quote derived from foreign exchange rate, use CbrForeignExchangeRateService
            } else if (securityType == ASSET) {
                return; // moex has no quotes for arbitrary assets
            } else if (moexClient.isDerivativeAndExpired(security.getTicker(), securityType)) {
                return;
            }
            String isinOrContractName = Optional.ofNullable(security.getIsin())
                    .or(() -> Optional.ofNullable(security.getTicker()))
                    .orElseThrow();
            moexClient.getSecId(isinOrContractName, securityType)
                    .flatMap(secId -> moexClient.getMarket(secId)
                            .map(market -> new QuoteRequest(secId, market)))
                    .ifPresentOrElse(
                            request -> updateQuotes(securityId, request),
                            () -> log.debug("Котировка не обновлена. На сайте МосБиржи отсутствует котировка {}", securityId));
        } catch (Exception e) {
            log.debug("Котировка не обновлена для {}", security, e);
        }
    }

    private void updateQuotes(Integer securityId, QuoteRequest request) {
        moexClient.getQuote(request.secId(), request.market())
                .ifPresent(quote -> saveQuote(securityId, quote));
        moexClient.getCurrentQuote(request.secId(), request.market())
                .map(quote -> quote.toBuilder().security(securityId).build())
                .ifPresent(quote -> currentQuotes.put(securityId, quote));
    }

    public Optional<SecurityQuote> getCurrentQuote(Integer securityId) {
        return Optional.ofNullable(currentQuotes.get(securityId));
    }

    public Optional<Instant> getLastCurrentQuoteTimestamp() {
        return currentQuotes.values().stream()
                .map(SecurityQuote::getTimestamp)
                .max(Instant::compareTo);
    }

    private void saveQuote(Integer securityId, SecurityQuote quote) {
        try {
            quote = quote.toBuilder()
                    .security(securityId)
                    .build();
            securityQuoteRepository.save(securityQuoteConverter.toEntity(quote));
        } catch (Exception e) {
            if (isUniqIndexViolationException(e)) {
                log.debug("Дублирование информации о котировке {}", quote);
                log.trace("Дублирование вызвано исключением", e);
            } else {
                log.warn("Не могу добавить информацию о котировке финансового инструмента {}", securityId, e);
            }
        }
    }

    private record QuoteRequest(String secId, MoexMarketDescription market) {
    }
}
