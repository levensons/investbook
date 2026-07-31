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
import org.springframework.stereotype.Service;
import ru.investbook.entity.SecurityQuoteEntity;
import ru.investbook.repository.SecurityQuoteRepository;
import ru.investbook.repository.SecurityRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

@Service
@Slf4j
@RequiredArgsConstructor
public class MoexIssSecurityQuoteUpdateService {

    private static final int MAX_PARALLEL_REQUESTS = 8;

    private final MoexIssSecurityQuoteService securityQuoteService;
    private final SecurityRepository securityRepository;
    private final SecurityQuoteRepository securityQuoteRepository;

    public synchronized String updateQuotes() throws InterruptedException, ExecutionException {
        long t0 = System.nanoTime();
        int parallelism = Math.min(MAX_PARALLEL_REQUESTS, Math.max(2, Runtime.getRuntime().availableProcessors()));
        try (ForkJoinPool pool = new ForkJoinPool(parallelism)) {
            pool.submit(() -> securityRepository.findAll()
                            .parallelStream()
                            .forEach(securityQuoteService::updateQuote))
                    .get();
        }
        String message = securityQuoteRepository.findFirstByOrderByTimestampDesc()
                .map(SecurityQuoteEntity::getTimestamp)
                .map(instant -> LocalDate.ofInstant(instant, ZoneId.systemDefault()))
                .map(latestQuoteDate -> "Котировки обновлены по " + latestQuoteDate + " включительно")
                .orElse("Запрос выполнен, но МосБиржа не вернула котировок");
        log.info("{} за {}", message, Duration.ofNanos(System.nanoTime() - t0));
        return message;
    }
}
