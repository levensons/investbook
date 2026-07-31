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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "investbook.market-data-auto-update", havingValue = "true")
public class MarketDataUpdateScheduler {

    private final MoexIssSecurityQuoteUpdateService quoteUpdateService;

    @Scheduled(
            fixedDelayString = "${investbook.market-data-update-interval:PT1H}",
            initialDelayString = "${investbook.market-data-update-initial-delay:PT1M}")
    public void updateQuotes() {
        try {
            log.info("Запущено автоматическое обновление котировок Московской биржи");
            quoteUpdateService.updateQuotes();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Автоматическое обновление котировок прервано", e);
        } catch (Exception e) {
            log.warn("Не удалось автоматически обновить котировки Московской биржи", e);
        }
    }
}
