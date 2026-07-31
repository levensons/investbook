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
import ru.investbook.entity.TransactionCashFlowEntity;
import ru.investbook.entity.TransactionEntity;
import ru.investbook.repository.AccountCashRepository;
import ru.investbook.repository.AccountPropertyRepository;
import ru.investbook.repository.EventCashFlowRepository;
import ru.investbook.repository.SecurityQuoteRepository;
import ru.investbook.repository.SecurityRepository;
import ru.investbook.repository.TransactionCashFlowRepository;
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
import static ru.investbook.report.ForeignExchangeRateService.RUB;

@Service
@RequiredArgsConstructor
@Slf4j
public class DailyPortfolioValueService {

    private static final int QUOTE_LOOKBACK_DAYS = 30;
    private static final Set<String> TOTAL_ASSETS_PROPERTIES = Set.of(
            TOTAL_ASSETS_RUB.name(),
            TOTAL_ASSETS_USD.name());

    private final TransactionRepository transactionRepository;
    private final SecurityRepository securityRepository;
    private final SecurityQuoteRepository securityQuoteRepository;
    private final TransactionCashFlowRepository transactionCashFlowRepository;
    private final AccountCashRepository accountCashRepository;
    private final AccountPropertyRepository accountPropertyRepository;
    private final EventCashFlowRepository eventCashFlowRepository;
    private final ForeignExchangeRateService foreignExchangeRateService;
    private final MoexHistoricalQuoteService moexHistoricalQuoteService;

    public List<DailyPortfolioValue> getDailyValues(ViewFilter filter) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate requestedFrom = LocalDate.ofInstant(filter.getFromDate(), zoneId);
        LocalDate requestedTo = LocalDate.ofInstant(filter.getToDate(), zoneId);
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashByDate =
                getCashSnapshots(filter.getAccounts(), zoneId);
        LocalDate cashChangesTo = getCashChangesTo(requestedTo, cashByDate);
        Instant cashChangesToExclusive = cashChangesTo.plusDays(1).atStartOfDay(zoneId).toInstant();
        List<TransactionEntity> cashTransactions = getTransactions(
                filter.getAccounts(),
                cashChangesToExclusive);
        List<TransactionEntity> transactions = cashTransactions.stream()
                .filter(transaction -> !transaction.getTimestamp().isAfter(filter.getToDate()))
                .toList();
        if (transactions.isEmpty()) {
            return List.of();
        }

        LocalDate firstTransactionDate = toDate(transactions.getFirst().getTimestamp(), zoneId);
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> transactionCashChanges =
                getTransactionCashChanges(cashTransactions, zoneId);
        CashFlows cashFlows = getCashFlows(
                filter.getAccounts(),
                cashChangesTo,
                zoneId);
        Map<AccountCurrency, BigDecimal> initialCash = inferInitialCash(
                cashByDate,
                transactionCashChanges,
                cashFlows.cashChangesByDate());
        LocalDate calculationFrom = firstTransactionDate;
        for (LocalDate cashChangeDate : cashFlows.cashChangesByDate().keySet()) {
            if (cashChangeDate.isBefore(calculationFrom)) {
                calculationFrom = cashChangeDate;
            }
        }
        LocalDate valuationFrom = requestedFrom.isAfter(calculationFrom) ? requestedFrom : calculationFrom;
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

        return calculateDailyValues(
                calculationFrom,
                requestedTo,
                valuationFrom,
                initialCash,
                transactionsByDate,
                transactionCashChanges,
                cashByDate,
                brokerAssets,
                cashFlows,
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
            ZoneId zoneId) {
        List<AccountCashEntity> cash = accounts.isEmpty() ?
                accountCashRepository.findAll() :
                accountCashRepository.findByAccountIn(accounts);
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> result = new TreeMap<>();
        cash.forEach(value -> result
                        .computeIfAbsent(toDate(value.getTimestamp(), zoneId), _ -> new LinkedHashMap<>())
                        .merge(
                                new AccountCurrency(value.getAccount(), normalizeCurrency(value.getCurrency())),
                                value.getValue(),
                                BigDecimal::add));
        return result;
    }

    private Map<LocalDate, Map<AccountCurrency, BigDecimal>> getTransactionCashChanges(
            List<TransactionEntity> transactions,
            ZoneId zoneId) {
        Map<Integer, TransactionEntity> transactionsById = transactions.stream()
                .collect(toMap(transaction -> requireNonNull(transaction.getId()), identity()));
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> result = new HashMap<>();
        for (TransactionCashFlowEntity cashFlow :
                transactionCashFlowRepository.findByTransactionIdIn(
                        new ArrayList<>(transactionsById.keySet()))) {
            TransactionEntity transaction = requireNonNull(transactionsById.get(cashFlow.getTransactionId()));
            LocalDate date = toDate(transaction.getTimestamp(), zoneId);
            result.computeIfAbsent(date, _ -> new HashMap<>())
                    .merge(
                            new AccountCurrency(
                                    transaction.getAccount(),
                                    normalizeCurrency(cashFlow.getCurrency())),
                            cashFlow.getValue(),
                            BigDecimal::add);
        }
        return result;
    }

    private CashFlows getCashFlows(
            Collection<String> accounts,
            LocalDate to,
            ZoneId zoneId) {
        Instant fromInstant = Instant.EPOCH;
        Instant toInstant = to.plusDays(1).atStartOfDay(zoneId).toInstant().minusSeconds(1);
        List<EventCashFlowEntity> cashFlows = accounts.isEmpty() ?
                eventCashFlowRepository.findByTimestampBetweenOrderByTimestamp(
                        fromInstant,
                        toInstant) :
                eventCashFlowRepository.findByAccountIdInAndTimestampBetweenOrderByTimestamp(
                        accounts,
                        fromInstant,
                        toInstant);
        Map<LocalDate, BigDecimal> rubByDate = new HashMap<>();
        Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashChangesByDate = new HashMap<>();
        for (EventCashFlowEntity cashFlow : cashFlows) {
            LocalDate date = toDate(cashFlow.getTimestamp(), zoneId);
            if (date.isAfter(to)) {
                continue;
            }
            if (cashFlow.getCashFlowType().getId() == CashFlowType.CASH.getId()) {
                rubByDate.merge(
                        date,
                        convertToRub(cashFlow.getValue(), cashFlow.getCurrency()),
                        BigDecimal::add);
            }
            cashChangesByDate.computeIfAbsent(date, _ -> new HashMap<>())
                    .merge(
                            new AccountCurrency(
                                    cashFlow.getAccount().getId(),
                                    normalizeCurrency(cashFlow.getCurrency())),
                            cashFlow.getValue(),
                            BigDecimal::add);
        }
        return new CashFlows(rubByDate, cashChangesByDate);
    }

    private static LocalDate getCashChangesTo(
            LocalDate requestedTo,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashByDate) {
        Set<AccountCurrency> accountsWithSnapshot = new HashSet<>();
        LocalDate result = requestedTo;
        for (Map.Entry<LocalDate, Map<AccountCurrency, BigDecimal>> entry : cashByDate.entrySet()) {
            for (AccountCurrency accountCurrency : entry.getValue().keySet()) {
                if (accountsWithSnapshot.add(accountCurrency) && entry.getKey().isAfter(result)) {
                    result = entry.getKey();
                }
            }
        }
        return result;
    }

    static Map<AccountCurrency, BigDecimal> inferInitialCash(
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashByDate,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> transactionCashChanges,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> eventCashChanges) {
        Map<AccountCurrency, Map.Entry<LocalDate, BigDecimal>> firstSnapshots = new HashMap<>();
        cashByDate.forEach((date, values) -> values.forEach((accountCurrency, value) ->
                firstSnapshots.putIfAbsent(accountCurrency, Map.entry(date, value))));
        Map<AccountCurrency, BigDecimal> result = new HashMap<>();
        firstSnapshots.forEach((accountCurrency, snapshot) -> {
            BigDecimal knownChanges = sumCashChangesUntil(
                    transactionCashChanges,
                    accountCurrency,
                    snapshot.getKey()).add(sumCashChangesUntil(
                            eventCashChanges,
                            accountCurrency,
                            snapshot.getKey()));
            result.put(accountCurrency, snapshot.getValue().subtract(knownChanges));
        });
        return result;
    }

    private static BigDecimal sumCashChangesUntil(
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> changesByDate,
            AccountCurrency accountCurrency,
            LocalDate to) {
        return changesByDate.entrySet().stream()
                .filter(entry -> !entry.getKey().isAfter(to))
                .map(entry -> entry.getValue().getOrDefault(accountCurrency, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
            if (property.getProperty().equals(TOTAL_ASSETS_USD.name())) {
                value = convertToRub(value, "USD");
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
            Map<AccountCurrency, BigDecimal> initialCash,
            Map<LocalDate, List<TransactionEntity>> transactionsByDate,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> transactionCashChanges,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashByDate,
            Map<LocalDate, BigDecimal> brokerAssetsByDate,
            CashFlows cashFlows,
            Map<Integer, TreeMap<LocalDate, SecurityQuoteEntity>> quotes) {
        Map<Integer, Long> positions = new HashMap<>();
        Map<AccountCurrency, BigDecimal> cash = new HashMap<>(initialCash);
        List<DailyPortfolioValue> result = new ArrayList<>();
        @Nullable BigDecimal previousAssets = null;
        BigDecimal cumulativeGrowth = BigDecimal.ONE;
        BigDecimal cashFlowSincePreviousPoint = BigDecimal.ZERO;
        BigDecimal cashReconciliationSincePreviousPoint = BigDecimal.ZERO;
        BigDecimal reconciliation = BigDecimal.ZERO;

        for (LocalDate date = calculationFrom; !date.isAfter(calculationTo); date = date.plusDays(1)) {
            for (TransactionEntity transaction : transactionsByDate.getOrDefault(date, List.of())) {
                positions.merge(
                        transaction.getSecurity().getId(),
                        (long) transaction.getCount(),
                        Long::sum);
            }
            mergeCashChanges(cash, transactionCashChanges.getOrDefault(date, Map.of()));
            mergeCashChanges(cash, cashFlows.cashChangesByDate().getOrDefault(date, Map.of()));
            cashReconciliationSincePreviousPoint = cashReconciliationSincePreviousPoint.add(
                    applyCashSnapshot(cash, cashByDate.getOrDefault(date, Map.of())));
            cashFlowSincePreviousPoint = cashFlowSincePreviousPoint.add(
                    cashFlows.externalRubByDate().getOrDefault(date, BigDecimal.ZERO));

            boolean brokerValuationDay = brokerAssetsByDate.containsKey(date);
            if ((!brokerValuationDay && date.isBefore(outputFrom)) ||
                    !isValuationDay(date, transactionsByDate, cashByDate, brokerAssetsByDate, quotes)) {
                continue;
            }
            @Nullable BigDecimal assets = calculateAssetsRub(date, positions, cash, quotes);
            if (assets == null) {
                continue;
            }
            @Nullable BigDecimal brokerAssets = brokerAssetsByDate.get(date);
            BigDecimal reconciliationChange = BigDecimal.ZERO;
            if (brokerAssets != null) {
                BigDecimal updatedReconciliation = brokerAssets.subtract(assets);
                reconciliationChange = updatedReconciliation.subtract(reconciliation);
                reconciliation = updatedReconciliation;
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
                BigDecimal assetsBeforeExternalCashFlow = assets
                        .subtract(cashFlowSincePreviousPoint)
                        .subtract(cashReconciliationSincePreviousPoint)
                        .subtract(reconciliationChange);
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
            cashReconciliationSincePreviousPoint = BigDecimal.ZERO;
        }
        return result;
    }

    private static void mergeCashChanges(
            Map<AccountCurrency, BigDecimal> cash,
            Map<AccountCurrency, BigDecimal> changes) {
        changes.forEach((accountCurrency, value) ->
                cash.merge(accountCurrency, value, BigDecimal::add));
    }

    private BigDecimal applyCashSnapshot(
            Map<AccountCurrency, BigDecimal> cash,
            Map<AccountCurrency, BigDecimal> snapshot) {
        BigDecimal adjustmentRub = BigDecimal.ZERO;
        for (Map.Entry<AccountCurrency, BigDecimal> entry : snapshot.entrySet()) {
            AccountCurrency accountCurrency = entry.getKey();
            BigDecimal previous = cash.getOrDefault(accountCurrency, BigDecimal.ZERO);
            BigDecimal updated = entry.getValue();
            cash.put(accountCurrency, updated);
            adjustmentRub = adjustmentRub.add(convertToRub(
                    updated.subtract(previous),
                    accountCurrency.currency()));
        }
        return adjustmentRub;
    }

    private @Nullable BigDecimal calculateAssetsRub(
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
            result = result.add(convertToRub(value, quoteCurrency));
        }
        for (Map.Entry<AccountCurrency, BigDecimal> cashEntry : cash.entrySet()) {
            result = result.add(convertToRub(
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

    private BigDecimal convertToRub(BigDecimal value, String currency) {
        return foreignExchangeRateService.convertValueToCurrency(
                value,
                normalizeCurrency(currency),
                RUB);
    }

    private static LocalDate toDate(Instant instant, ZoneId zoneId) {
        return LocalDate.ofInstant(instant, zoneId);
    }

    private static String normalizeCurrency(String currency) {
        return currency.equalsIgnoreCase("RUR") || currency.equalsIgnoreCase("SUR") ? "RUB" : currency;
    }

    record AccountCurrency(String account, String currency) {
    }

    private record CashFlows(
            Map<LocalDate, BigDecimal> externalRubByDate,
            Map<LocalDate, Map<AccountCurrency, BigDecimal>> cashChangesByDate) {
    }
}
