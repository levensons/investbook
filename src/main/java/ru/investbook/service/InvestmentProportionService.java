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
import org.spacious_team.broker.pojo.Security;
import org.spacious_team.broker.pojo.SecurityType;
import org.springframework.stereotype.Service;
import ru.investbook.converter.SecurityConverter;
import ru.investbook.entity.SecurityDescriptionEntity;
import ru.investbook.entity.SecurityPositionEntity;
import ru.investbook.report.FifoPositionsFactory;
import ru.investbook.report.FifoPositionsFilter;
import ru.investbook.repository.SecurityDescriptionRepository;
import ru.investbook.repository.SecurityPositionRepository;
import ru.investbook.repository.SecurityRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static java.lang.System.nanoTime;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.*;
import static org.spacious_team.broker.pojo.SecurityType.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvestmentProportionService {

    private final Set<SecurityType> stockAndBondTypes = Set.of(STOCK, BOND, STOCK_OR_BOND);
    private final SecurityRepository securityRepository;
    private final SecurityConverter securityConverter;
    private final SecurityDescriptionRepository securityDescriptionRepository;
    private final FifoPositionsFactory fifoPositionsFactory;
    private final SecurityPositionRepository securityPositionRepository;
    private final CurrentSecurityPriceService currentSecurityPriceService;

    /**
     * Sector name -> value in rub
     */
    public Map<String, Float> getSectorsProportion(Set<String> accounts) {
        try {
            long t0 = nanoTime();
            Map<String, Float> result = getSecurityInvestmentStream(accounts)
                    .collect(groupingBy(this::getEconomicSector,
                            mapping(SecurityInvestment::getInvestment, reducing(0f, Float::sum))));
            log.info("Рассчитаны объемы инвестиций в сектора экономики за {}", Duration.ofNanos(nanoTime() - t0));
            return result;
        } catch (Exception e) {
            String message = "Ошибка при расчете объемов инвестиций в сектора экономики";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Security name -> value in rub
     */
    public Map<String, Float> getSecuritiesProportion(Set<String> accounts) {
        try {
            long t0 = nanoTime();
            Map<String, Float> result = getSecurityInvestmentStream(accounts)
                    .collect(toMap(
                            this::getSecurityDescription,
                            SecurityInvestment::getInvestment,
                            Float::sum));
            log.info("Рассчитаны объемы инвестиций в бумаги за {}", Duration.ofNanos(nanoTime() - t0));
            return result;
        } catch (Exception e) {
            String message = "Ошибка при расчете объемов инвестиций в бумаги";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    private Stream<SecurityInvestment> getSecurityInvestmentStream(Set<String> accounts) {
        List<SecurityInvestment> reportedInvestments = new ArrayList<>();
        Set<String> accountsWithoutReportedPositions = new HashSet<>(accounts);
        Map<Integer, ReportedPosition> reportedPositions = new HashMap<>();
        for (String account : accounts) {
            securityPositionRepository.findFirstByAccountOrderByTimestampDesc(account)
                    .map(SecurityPositionEntity::getTimestamp)
                    .ifPresent(timestamp -> {
                        accountsWithoutReportedPositions.remove(account);
                        securityPositionRepository.findByAccountAndTimestamp(account, timestamp)
                                .forEach(position -> addReportedPosition(reportedPositions, position));
                    });
        }
        reportedPositions.values().stream()
                .map(position -> getCurrentAmount(position.security(), position.count()))
                .flatMap(Optional::stream)
                .forEach(reportedInvestments::add);

        Stream<SecurityInvestment> transactionInvestments = accountsWithoutReportedPositions.isEmpty() ?
                Stream.empty() :
                getTransactionInvestmentStream(FifoPositionsFilter.of(accountsWithoutReportedPositions));
        return Stream.concat(reportedInvestments.stream(), transactionInvestments)
                .filter(v -> v.investment().floatValue() > 1);
    }

    private void addReportedPosition(
            Map<Integer, ReportedPosition> reportedPositions,
            SecurityPositionEntity position) {
        Security security = securityConverter.fromEntity(position.getSecurity());
        Integer securityId = requireNonNull(security.getId());
        reportedPositions.merge(
                securityId,
                new ReportedPosition(security, position.getCount()),
                (left, right) -> new ReportedPosition(left.security(), left.count().add(right.count())));
    }

    private Stream<SecurityInvestment> getTransactionInvestmentStream(FifoPositionsFilter filter) {
        return securityRepository.findByTypeIn(stockAndBondTypes)
                .stream()
                .map(securityConverter::fromEntity)
                .map(security -> getCurrentAmount(security, filter))
                .flatMap(Optional::stream);
    }

    private Optional<SecurityInvestment> getCurrentAmount(Security security, FifoPositionsFilter filter) {
        return getOpenedPositionsCostByCurrentOrLastTransactionQuoteInRub(security, filter)
                .map(investmentRub -> new SecurityInvestment(security, investmentRub));
    }

    private Optional<SecurityInvestment> getCurrentAmount(Security security, BigDecimal count) {
        if (count.signum() == 0) {
            return Optional.empty();
        }
        return currentSecurityPriceService.getDirtyPriceInRub(security)
                .map(price -> new SecurityInvestment(security, price.multiply(count)));
    }

    public Optional<BigDecimal> getOpenedPositionsCostByCurrentOrLastTransactionQuoteInRub(Security security,
                                                                                           FifoPositionsFilter filter) {
        return currentSecurityPriceService.getDirtyPriceInRub(security)
                .map(quote -> quote.multiply(
                        BigDecimal.valueOf(
                                fifoPositionsFactory.get(security, filter)
                                        .getCurrentOpenedPositionsCount())));
    }

    private String getEconomicSector(SecurityInvestment securityInvestment) {
        return securityDescriptionRepository.findById(requireNonNull(securityInvestment.security().getId()))
                .map(SecurityDescriptionEntity::getSector)
                .orElse(SecuritySectorService.UNKNOWN_SECTOR);
    }

    private String getSecurityDescription(SecurityInvestment securityInvestment) {
        Security security = securityInvestment.security();
        return ofNullable(security.getName())
                .or(() -> ofNullable(security.getTicker()))
                .or(() -> ofNullable(security.getIsin()))
                .orElseGet(() -> String.valueOf(security.getId()));
    }

    private record SecurityInvestment(Security security, BigDecimal investment) {
        private float getInvestment() {
            return investment == null ? 0 : investment.floatValue();
        }
    }

    private record ReportedPosition(Security security, BigDecimal count) {
    }
}
