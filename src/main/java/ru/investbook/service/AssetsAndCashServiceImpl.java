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

package ru.investbook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.spacious_team.broker.pojo.AccountCash;
import org.spacious_team.broker.pojo.AccountPropertyType;
import org.spacious_team.broker.pojo.Security;
import org.spacious_team.broker.pojo.SecurityType;
import org.springframework.stereotype.Service;
import ru.investbook.converter.AccountCashConverter;
import ru.investbook.converter.SecurityConverter;
import ru.investbook.entity.AccountCashEntity;
import ru.investbook.entity.AccountPropertyEntity;
import ru.investbook.report.FifoPositionsFilter;
import ru.investbook.report.ForeignExchangeRateService;
import ru.investbook.repository.AccountCashRepository;
import ru.investbook.repository.AccountPropertyRepository;
import ru.investbook.repository.AccountRepository;
import ru.investbook.repository.SecurityRepository;
import ru.investbook.repository.SecurityPositionRepository;
import ru.investbook.web.ControllerHelper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static java.lang.System.nanoTime;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.spacious_team.broker.pojo.SecurityType.*;
import static ru.investbook.report.ForeignExchangeRateService.RUB;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssetsAndCashServiceImpl implements AssetsAndCashService {
    private final Set<SecurityType> stockBondAndAssetTypes = Set.of(STOCK, BOND, STOCK_OR_BOND, ASSET);
    private final AccountPropertyRepository accountPropertyRepository;
    private final ForeignExchangeRateService foreignExchangeRateService;
    private final InvestmentProportionService investmentProportionService;
    private final CurrentSecurityPriceService currentSecurityPriceService;
    private final AccountRepository accountRepository;
    private final SecurityRepository securityRepository;
    private final SecurityPositionRepository securityPositionRepository;
    private final SecurityConverter securityConverter;
    private final AccountCashRepository accountCashRepository;
    private final AccountCashConverter accountCashConverter;

    @Override
    public Set<String> getActiveAccounts() {
        return ControllerHelper.getActiveAccounts(accountRepository);
    }

    @Override
    public Optional<BigDecimal> getTotalAssetsInRub(Collection<String> accounts) {
        Collection<BigDecimal> accountAssets = accounts.stream()
                .map(this::getTotalAssetsInRub)
                .flatMap(Optional::stream)
                .toList();
        if (accountAssets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(accountAssets.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));

    }

    @Override
    public Optional<BigDecimal> getTotalAssetsAtCurrentPricesInRub(Collection<String> accounts) {
        Collection<BigDecimal> accountAssets = accounts.stream()
                .map(account -> getTotalAssetsByLatestBrokerPositionsAtCurrentPricesInRub(account)
                        .or(() -> getTotalAssetsByBrokerEstimationInRub(account))
                        .or(() -> getTotalAssetsByCurrentOrLastTransactionQuoteEstimationInRub(account)))
                .flatMap(Optional::stream)
                .toList();
        if (accountAssets.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(accountAssets.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Override
    public Optional<BigDecimal> getTotalAssetsInRub(String account) {
        return getTotalAssetsByBrokerEstimationInRub(account)
                .or(() -> getTotalAssetsByCurrentOrLastTransactionQuoteEstimationInRub(account));
    }

    private Optional<BigDecimal> getTotalAssetsByBrokerEstimationInRub(String account) {
        Collection<AccountPropertyEntity> assets = new ArrayList<>(2);
        getTotalAssets(account, AccountPropertyType.TOTAL_ASSETS_RUB).ifPresent(assets::add);
        getTotalAssets(account, AccountPropertyType.TOTAL_ASSETS_USD).ifPresent(assets::add);
        // groups by date
        TreeMap<Instant, List<AccountPropertyEntity>> assetsGroupedByDate = assets.stream()
                .collect(groupingBy(AccountPropertyEntity::getTimestamp, TreeMap::new, toList()));
        // finds last day assets
        return Optional.ofNullable(assetsGroupedByDate.lastEntry()) // finds last date assets
                .map(Map.Entry::getValue)
                .map(Collection::stream)
                .map(stream -> stream.map(e -> foreignExchangeRateService.convertValueToCurrency(
                                parseTotalAssetsIfCan(e), getCurrency(e), RUB))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * Values the actual security balances from the latest broker report at the latest available market prices.
     */
    private Optional<BigDecimal> getTotalAssetsByLatestBrokerPositionsAtCurrentPricesInRub(String account) {
        return securityPositionRepository.findFirstByAccountOrderByTimestampDesc(account)
                .map(position -> position.getTimestamp())
                .flatMap(timestamp -> {
                    List<Optional<BigDecimal>> positionValues = securityPositionRepository
                            .findByAccountAndTimestamp(account, timestamp)
                            .stream()
                            .filter(position -> position.getCount().signum() != 0)
                            .map(position -> currentSecurityPriceService
                                    .getDirtyPriceInRub(securityConverter.fromEntity(position.getSecurity()))
                                    .map(price -> price.multiply(position.getCount())))
                            .toList();
                    if (positionValues.stream().anyMatch(Optional::isEmpty)) {
                        return Optional.empty();
                    }
                    BigDecimal securitiesValue = positionValues.stream()
                            .flatMap(Optional::stream)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal cash = getTotalCashInRub(Set.of(account)).orElse(BigDecimal.ZERO);
                    return Optional.of(securitiesValue.add(cash));
                });
    }

    private Optional<AccountPropertyEntity> getTotalAssets(String account, AccountPropertyType currency) {
        return accountPropertyRepository
                .findFirstByAccountIdAndPropertyOrderByTimestampDesc(account, currency.name());
    }

    private static String getCurrency(AccountPropertyEntity entity) {
        int length = entity.getProperty().length();
        return entity.getProperty().substring(length - 3, length);
    }

    private static BigDecimal parseTotalAssetsIfCan(AccountPropertyEntity entity) {
        try {
            return BigDecimal.valueOf(
                    Double.parseDouble(
                            entity.getValue()));
        } catch (Exception e) {
            log.error("Значение должно содержать число, сохранено {}", entity);
            return BigDecimal.ZERO;
        }
    }

    private Optional<BigDecimal> getTotalAssetsByCurrentOrLastTransactionQuoteEstimationInRub(String account) {
        try {
            long t0 = nanoTime();
            FifoPositionsFilter filter = FifoPositionsFilter.of(account);
            List<BigDecimal> openedPositions = securityRepository.findByTypeIn(stockBondAndAssetTypes)
                    .stream()
                    .map(securityConverter::fromEntity)
                    .map(security -> investmentProportionService
                            .getOpenedPositionsCostByCurrentOrLastTransactionQuoteInRub(security, filter))
                    .flatMap(Optional::stream)
                    .filter(value -> value.signum() != 0)
                    .toList();
            Optional<BigDecimal> cash = getTotalCashInRub(Set.of(account));
            log.debug("Оценена стоимость активов по котировкам за {}", Duration.ofNanos(nanoTime() - t0));
            if (openedPositions.isEmpty() && cash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(openedPositions.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(cash.orElse(BigDecimal.ZERO)));
        } catch (Exception e) {
            String message = "Ошибка оценки стоимости активов по котировкам";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    @Override
    public Optional<BigDecimal> getTotalCashInRub(Collection<String> accounts) {
        Collection<BigDecimal> accountCash =  getAccountCash(accounts, Instant.now())
                .stream()
                .map(cash -> foreignExchangeRateService.convertValueToCurrency(cash.getValue(), cash.getCurrency(), RUB))
                .toList();
        if (accountCash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(accountCash.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Override
    public List<AccountCash> getAccountCash(Collection<String> accounts, Instant atInstant) {
        List<AccountCashEntity> entities = accounts.isEmpty() ?
                accountCashRepository.findDistinctOnAccountByTimestampBetweenOrderByTimestampDesc(
                        Instant.EPOCH,
                        atInstant) :
                accountCashRepository.findDistinctOnAccountByAccountInAndTimestampBetweenOrderByTimestampDesc(
                        accounts,
                        Instant.EPOCH,
                        atInstant);
        return entities.stream()
                .map(accountCashConverter::fromEntity)
                .toList();
    }
}
