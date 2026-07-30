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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spacious_team.broker.pojo.CashFlowType;
import org.springframework.stereotype.Service;
import ru.investbook.entity.AccountCashEntity;
import ru.investbook.entity.AccountPropertyEntity;
import ru.investbook.entity.EventCashFlowEntity;
import ru.investbook.entity.SecurityEntity;
import ru.investbook.entity.SecurityQuoteEntity;
import ru.investbook.entity.TransactionEntity;
import ru.investbook.repository.AccountCashRepository;
import ru.investbook.repository.AccountPropertyRepository;
import ru.investbook.repository.EventCashFlowRepository;
import ru.investbook.repository.SecurityQuoteRepository;
import ru.investbook.repository.SecurityRepository;
import ru.investbook.repository.TransactionRepository;
import ru.investbook.service.moex.MoexHistoricalQuoteService;
import ru.investbook.web.model.DailyPortfolioValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.util.function.Function.identity;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
import static org.spacious_team.broker.pojo.AccountPropertyType.TOTAL_ASSETS_RUB;
import static org.spacious_team.broker.pojo.AccountPropertyType.TOTAL_ASSETS_USD;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyPortfolioValueService {

    private static final String USD = "USD";
    private static final int QUOTE_LOOKBACK_DAYS = 30;
    private static final Set<String> TOTAL_ASSETS_PROPERTIES = Set.of(
            TOTAL_ASSETS_RUB.name(),
            TOTAL_ASSETS_USD.name());

    private final TransactionRepository transactionRepository;
    private final SecurityRepository securityRepository;
    private final SecurityQuoteRepository securityQuoteRepository;
    private final AccountCashRepository accountCashRepository;
    private final AccountPropertyRepository accountPropertyRepository;
    private final EventCashFlowRepository eventCashFlowRepository;
    private final ForeignExchangeRateService foreignExchangeRateService;
    private final MoexHistoricalQuoteService moexHistoricalQuoteService;

    public List<DailyPortfolioValue> getDailyValues(ViewFilter filter) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate requestedFrom = LocalDate.ofInstant(filter.getFromDate(), zoneId);
        LocalDate requestedTo = LocalDate.ofInstant(filter.getToDate(), zoneId);
        List<TransactionEntity> transactions = getTransactions(filter.getAccounts(), filter.getToDate());
        if (transactions.isEmpty()) {
            return List.of();
        }

        LocalDate firstTransactionDate = toDate(transactions.getFirst().getTimestamp(), zoneId);
        LocalDate valuationFrom = requestedFrom.isAfter(firstTransactionDate) ? requestedFrom : firstTransactionDate;
        Set<Integer> securityIds = transactions.stream()
                .map(transaction -> transaction.getSecurity().getId())
                .collect(HashSet::new, Set::add, Set::addAll);
        Map<Integer, SecurityEntity> securities = securityRepository.findAllById(securityIds)
                .stream()
                .collect(toMap(SecurityEntity::getId, identity()));
        Map<LocalDate, BigDecimal> brokerAssets =
                getBrokerAssets(filter.getAccounts(), filter.getToDate(), zoneId);
        LocalDate quoteHistoryFrom = brokerAssets.keySet()
                .stream()
                .filter(date -> !date.isAfter(valuationFrom))
                .max(LocalDate::compareTo)
                .map(date -> date.isBefore(valuationFrom) ? date : valuationFrom)
                .orElse(valuationFrom);

        moexHistoricalQuoteService.ensureHistory(securities.values(), quoteHistoryFrom, requestedTo);

        Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> quotes =
                getQuotes(securityIds, quoteHistoryFrom, requestedTo, zoneId);
        Map<LocalDate, List<TransactionEntity>> transactionsByDate = groupTransactionsByDate(transactions, zoneId);
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashByDate =
                getCashSnapshots(filter.getAccounts(), filter.getToDate(), zoneId);
        Map<LocalDate, BigDecimal> externalCashFlows =
                getExternalCashFlows(filter, requestedFrom, requestedTo, zoneId);

        return calculateDailyValues(
                firstTransactionDate,
                requestedTo,
                valuationFrom,
                transactionsByDate,
                cashByDate,
                brokerAssets,
                externalCashFlows,
                quotes);
    }

    private List<TransactionEntity> getTransactions(Collection<String> accounts, Instant to) {
        return accounts.isEmpty() ?
                transactionRepository.findByTimestampLessThanOrderByTimestampAscTradeIdAsc(to) :
                transactionRepository.findByAccountInAndTimestampLessThanOrderByTimestampAscTradeIdAsc(accounts, to);
    }

    private Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> getQuotes(
            Set<Integer> securityIds,
            LocalDate from,
            LocalDate to,
            ZoneId zoneId) {
        if (securityIds.isEmpty()) {
            return Map.of();
        }
        Instant fromInstant = from.minusDays(QUOTE_LOOKBACK_DAYS).atStartOfDay(zoneId).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zoneId).toInstant();
        Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> result = new HashMap<>();
        for (SecurityQuoteEntity quote : securityQuoteRepository
                .findBySecurityIdInAndTimestampBetweenOrderByTimestampAsc(
                        securityIds,
                        fromInstant,
                        toInstant)) {
            result.computeIfAbsent(quote.getSecurity().getId(), _ -> new TreeMap<>())
                    .put(toDate(quote.getTimestamp(), zoneId), quote);
        }
        return result;
    }

    private static Map<LocalDate, List<TransactionEntity>> groupTransactionsByDate(
            List<TransactionEntity> transactions,
            ZoneId zoneId) {
        Map<LocalDate, List<TransactionEntity>> result = new HashMap<>();
        for (TransactionEntity transaction : transactions) {
            result.computeIfAbsent(toDate(transaction.getTimestamp(), zoneId), _ -> new ArrayList<>())
                    .add(transaction);
        }
        return result;
    }

    private Map<LocalDate, Map<AccountCurrency, BigDecimal>> getCashSnapshots(
            Collection<String> accounts,
            Instant to,
            ZoneId zoneId) {
        List<AccountCashEntity> cash = accounts.isEmpty() ?
                accountCashRepository.findAll() :
                accountCashRepository.findByAccountIn(accounts);
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> result = new TreeMap<>();
        cash.stream()
                .filter(value -> !value.getTimestamp().isAfter(to))
                .forEach(value -> result
                        .computeIfAbsent(toDate(value.getTimestamp(), zoneId), _ -> new LinkedHashMap<>())
                        .merge(
                                new AccountCurrency(value.getAccount(), normalizeCurrency(value.getCurrency())),
                                value.getValue(),
                                BigDecimal::add));
        return result;
    }

    private Map<LocalDate, BigDecimal> getExternalCashFlows(
            ViewFilter filter,
            LocalDate from,
            LocalDate to,
            ZoneId zoneId) {
        List<EventCashFlowEntity> cashFlows = filter.getAccounts().isEmpty() ?
                eventCashFlowRepository.findByCashFlowTypeIdAndTimestampBetweenOrderByTimestamp(
                        CashFlowType.CASH.getId(),
                        filter.getFromDate(),
                        filter.getToDate()) :
                eventCashFlowRepository.findByAccountIdInAndCashFlowTypeIdAndTimestampBetweenOrderByTimestamp(
                        filter.getAccounts(),
                        CashFlowType.CASH.getId(),
                        filter.getFromDate(),
                        filter.getToDate());
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        for (EventCashFlowEntity cashFlow : cashFlows) {
            LocalDate date = toDate(cashFlow.getTimestamp(), zoneId);
            if (date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            result.merge(
                    date,
                    convertToUsd(cashFlow.getValue(), cashFlow.getCurrency()),
                    BigDecimal::add);
        }
        return result;
    }

    private Map<LocalDate, BigDecimal> getBrokerAssets(
            Collection<String> accounts,
            Instant to,
            ZoneId zoneId) {
        List<AccountPropertyEntity> properties = accounts.isEmpty() ?
                accountPropertyRepository.findByPropertyInAndTimestampBetweenOrderByTimestampAsc(
                        TOTAL_ASSETS_PROPERTIES,
                        Instant.EPOCH,
                        to) :
                accountPropertyRepository.findByAccountIdInAndPropertyInAndTimestampBetweenOrderByTimestampAsc(
                        accounts,
                        TOTAL_ASSETS_PROPERTIES,
                        Instant.EPOCH,
                        to);
        Set<String> expectedAccounts = accounts.isEmpty() ?
                properties.stream().map(property -> property.getAccount().getId()).collect(HashSet::new, Set::add, Set::addAll) :
                new HashSet<>(accounts);
        Map<Instant, Map<String, BigDecimal>> snapshots = new TreeMap<>();
        for (AccountPropertyEntity property : properties) {
            BigDecimal value;
            try {
                value = new BigDecimal(property.getValue());
            } catch (Exception e) {
                log.warn("Не удалось прочитать оценку активов {}", property);
                continue;
            }
            if (property.getProperty().equals(TOTAL_ASSETS_RUB.name())) {
                value = convertToUsd(value, "RUB");
            }
            snapshots.computeIfAbsent(property.getTimestamp(), _ -> new HashMap<>())
                    .merge(property.getAccount().getId(), value, BigDecimal::add);
        }

        Map<String, BigDecimal> lastAccountAssets = new HashMap<>();
        Map<LocalDate, BigDecimal> result = new TreeMap<>();
        snapshots.forEach((timestamp, values) -> {
            lastAccountAssets.putAll(values);
            if (lastAccountAssets.keySet().containsAll(expectedAccounts)) {
                result.put(
                        toDate(timestamp, zoneId),
                        expectedAccounts.stream()
                                .map(account -> requireNonNull(lastAccountAssets.get(account)))
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
            }
        });
        return result;
    }

    private List<DailyPortfolioValue> calculateDailyValues(
            LocalDate calculationFrom,
            LocalDate calculationTo,
            LocalDate outputFrom,
            Map<LocalDate, List<TransactionEntity>> transactionsByDate,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashByDate,
            Map<LocalDate, BigDecimal> brokerAssetsByDate,
            Map<LocalDate, BigDecimal> externalCashFlows,
            Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> quotes) {
        Map<Integer, Long> positions = new HashMap<>();
        Map<AccountCurrency, BigDecimal> cash = new HashMap<>();
        List<DailyPortfolioValue> result = new ArrayList<>();
        @Nullable BigDecimal previousAssets = null;
        BigDecimal cumulativeGrowth = BigDecimal.ONE;
        BigDecimal cashFlowSincePreviousPoint = BigDecimal.ZERO;
        BigDecimal reconciliation = BigDecimal.ZERO;

        for (LocalDate date = calculationFrom; !date.isAfter(calculationTo); date = date.plusDays(1)) {
            for (TransactionEntity transaction : transactionsByDate.getOrDefault(date, List.of())) {
                positions.merge(
                        transaction.getSecurity().getId(),
                        (long) transaction.getCount(),
                        Long::sum);
            }
            cash.putAll(cashByDate.getOrDefault(date, Map.of()));
            cashFlowSincePreviousPoint = cashFlowSincePreviousPoint.add(
                    externalCashFlows.getOrDefault(date, BigDecimal.ZERO));

            boolean brokerValuationDay = brokerAssetsByDate.containsKey(date);
            if ((!brokerValuationDay && date.isBefore(outputFrom)) ||
                    !isValuationDay(date, transactionsByDate, cashByDate, brokerAssetsByDate, quotes)) {
                continue;
            }
            @Nullable BigDecimal assets = calculateAssetsUsd(date, positions, cash, quotes);
            if (assets == null) {
                continue;
            }
            @Nullable BigDecimal brokerAssets = brokerAssetsByDate.get(date);
            if (brokerAssets != null) {
                reconciliation = brokerAssets.subtract(assets);
            }
            assets = assets.add(reconciliation);
            if (date.isBefore(outputFrom)) {
                continue;
            }
            double growth;
            if (previousAssets == null || previousAssets.signum() == 0) {
                cumulativeGrowth = BigDecimal.ONE;
                growth = 0;
            } else {
                BigDecimal assetsBeforeExternalCashFlow = assets.subtract(cashFlowSincePreviousPoint);
                BigDecimal periodGrowth = assetsBeforeExternalCashFlow.divide(
                        previousAssets,
                        20,
                        RoundingMode.HALF_UP);
                cumulativeGrowth = cumulativeGrowth.multiply(periodGrowth);
                growth = cumulativeGrowth.subtract(BigDecimal.ONE)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
            }
            result.add(new DailyPortfolioValue(date, assets.doubleValue(), growth));
            previousAssets = assets;
            cashFlowSincePreviousPoint = BigDecimal.ZERO;
        }
        return result;
    }

    private @Nullable BigDecimal calculateAssetsUsd(
            LocalDate date,
            Map<Integer, Long> positions,
            Map<AccountCurrency, BigDecimal> cash,
            Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> quotes) {
        BigDecimal result = BigDecimal.ZERO;
        for (Map.Entry<Integer, Long> position : positions.entrySet()) {
            long count = position.getValue();
            if (count == 0) {
                continue;
            }
            Integer securityId = position.getKey();
            @Nullable TreeMap<LocalDate, SecurityQuoteEntity> securityQuotes = quotes.get(securityId);
            if (securityQuotes == null) {
                log.debug("Нет котировок для инструмента {} на {}", securityId, date);
                return null;
            }
            Map.@Nullable Entry<LocalDate, SecurityQuoteEntity> quoteEntry = securityQuotes.floorEntry(date);
            if (quoteEntry == null) {
                log.debug("Нет котировки для инструмента {} до {}", securityId, date);
                return null;
            }
            SecurityQuoteEntity quote = quoteEntry.getValue();
            @Nullable BigDecimal price = quote.getPrice();
            BigDecimal unitPrice = price == null ? quote.getQuote() : price;
            @Nullable BigDecimal accruedInterest = quote.getAccruedInterest();
            if (accruedInterest != null) {
                unitPrice = unitPrice.add(accruedInterest);
            }
            @Nullable String currency = quote.getCurrency();
            String quoteCurrency = normalizeCurrency(currency == null ? "RUB" : currency);
            BigDecimal value = unitPrice.multiply(BigDecimal.valueOf(count));
            result = result.add(convertToUsd(value, quoteCurrency));
        }
        for (Map.Entry<AccountCurrency, BigDecimal> cashEntry : cash.entrySet()) {
            result = result.add(convertToUsd(
                    cashEntry.getValue(),
                    cashEntry.getKey().currency()));
        }
        return result;
    }

    private static boolean isValuationDay(
            LocalDate date,
            Map<LocalDate, List<TransactionEntity>> transactions,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> cash,
            Map<LocalDate, BigDecimal> brokerAssets,
            Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> quotes) {
        DayOfWeek day = date.getDayOfWeek();
        if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
            return true;
        }
        if (transactions.containsKey(date) || cash.containsKey(date) || brokerAssets.containsKey(date)) {
            return true;
        }
        return quotes.values().stream().anyMatch(values -> values.containsKey(date));
    }

    private BigDecimal convertToUsd(BigDecimal value, String currency) {
        return foreignExchangeRateService.convertValueToCurrency(
                value,
                normalizeCurrency(currency),
                USD);
    }

    private static LocalDate toDate(Instant instant, ZoneId zoneId) {
        return LocalDate.ofInstant(instant, zoneId);
    }

    private static String normalizeCurrency(String currency) {
        return currency.equalsIgnoreCase("RUR") || currency.equalsIgnoreCase("SUR") ? "RUB" : currency;
    }

    private record AccountCurrency(String account, String currency) {
    }
}
