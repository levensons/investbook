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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spacious_team.broker.pojo.SecurityQuote;
import org.springframework.stereotype.Service;
import ru.investbook.converter.SecurityQuoteConverter;
import ru.investbook.entity.SecurityEntity;
import ru.investbook.entity.SecurityQuoteEntity;
import ru.investbook.repository.SecurityQuoteRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.spacious_team.broker.pojo.SecurityType.ASSET;
import static org.spacious_team.broker.pojo.SecurityType.CURRENCY_PAIR;

@Service
@RequiredArgsConstructor
@Slf4j
public class MoexHistoricalQuoteService {

    private static final int HISTORY_LOOKBACK_DAYS = 14;

    private final MoexIssClient moexIssClient;
    private final SecurityQuoteRepository securityQuoteRepository;
    private final SecurityQuoteConverter securityQuoteConverter;
    private final Map<Integer, DateRange> loadedRanges = new ConcurrentHashMap<>();

    public synchronized void ensureHistory(
            Collection<SecurityEntity> securities,
            LocalDate from,
            LocalDate to) {
        LocalDate requestFrom = from.minusDays(HISTORY_LOOKBACK_DAYS);
        LocalDate requestTo = to.isBefore(LocalDate.now()) ? to : LocalDate.now().minusDays(1);
        if (requestTo.isBefore(requestFrom)) {
            return;
        }
        for (SecurityEntity security : securities) {
            if (security.getType() == ASSET || security.getType() == CURRENCY_PAIR) {
                continue;
            }
            Integer securityId = security.getId();
            @Nullable DateRange loadedRange = loadedRanges.get(securityId);
            if (loadedRange != null && loadedRange.contains(requestFrom, requestTo)) {
                continue;
            }
            try {
                String isinOrTicker = Optional.ofNullable(security.getIsin())
                        .or(() -> Optional.ofNullable(security.getTicker()))
                        .orElseThrow();
                List<SecurityQuote> quotes = moexIssClient.getQuoteHistory(
                        isinOrTicker,
                        security.getType(),
                        requestFrom,
                        requestTo);
                saveMissingQuotes(securityId, requestFrom, requestTo, quotes);
                loadedRanges.merge(
                        securityId,
                        new DateRange(requestFrom, requestTo),
                        DateRange::merge);
            } catch (Exception e) {
                log.warn("Не удалось загрузить историю котировок MOEX для {}", security, e);
            }
        }
    }

    private void saveMissingQuotes(
            Integer securityId,
            LocalDate from,
            LocalDate to,
            List<SecurityQuote> quotes) {
        ZoneId zoneId = ZoneId.systemDefault();
        Instant fromInstant = from.atStartOfDay(zoneId).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zoneId).toInstant();
        Set<Instant> existingTimestamps = securityQuoteRepository
                .findBySecurityIdAndTimestampBetweenOrderByTimestampAsc(
                        securityId,
                        fromInstant,
                        toInstant)
                .stream()
                .map(SecurityQuoteEntity::getTimestamp)
                .collect(HashSet::new, Set::add, Set::addAll);
        Set<Instant> newTimestamps = new HashSet<>();
        List<SecurityQuoteEntity> entities = quotes.stream()
                .filter(quote -> !existingTimestamps.contains(quote.getTimestamp()))
                .filter(quote -> newTimestamps.add(quote.getTimestamp()))
                .map(quote -> quote.toBuilder()
                        .security(securityId)
                        .build())
                .map(securityQuoteConverter::toEntity)
                .toList();
        if (!entities.isEmpty()) {
            securityQuoteRepository.saveAll(entities);
            log.debug("Сохранено {} дневных котировок MOEX для инструмента {}", entities.size(), securityId);
        }
    }

    private record DateRange(LocalDate from, LocalDate to) {

        private boolean contains(LocalDate requestedFrom, LocalDate requestedTo) {
            return !from.isAfter(requestedFrom) && !to.isBefore(requestedTo);
        }

        private DateRange merge(DateRange other) {
            LocalDate mergedFrom = from.isBefore(other.from) ? from : other.from;
            LocalDate mergedTo = to.isAfter(other.to) ? to : other.to;
            return new DateRange(mergedFrom, mergedTo);
        }
    }
}
