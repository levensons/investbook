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

package ru.investbook.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import ru.investbook.report.ViewFilter;
import ru.investbook.report.excel.AccountAnalysisChartService;
import ru.investbook.repository.AccountRepository;
import ru.investbook.web.model.AccountAnalysisChartPoint;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.time.ZoneId.systemDefault;
import static ru.investbook.web.ControllerHelper.getAccounts;
import static ru.investbook.web.ControllerHelper.getActiveAccounts;

@Controller
@RequestMapping("/charts/account-analysis")
@RequiredArgsConstructor
public class AccountAnalysisChartController {

    private final AccountAnalysisChartService chartService;
    private final AccountRepository accountRepository;

    @GetMapping
    public String getPage(Model model, HttpServletRequest request) {
        Set<String> activeAccounts = getActiveAccounts(accountRepository);
        LocalDate today = LocalDate.now();
        model.addAttribute("allAccounts", getAccounts(accountRepository));
        model.addAttribute("activeAccounts", activeAccounts);
        model.addAttribute(
                "defaultFromDate",
                activeAccounts.isEmpty() ? today.minusYears(1) :
                        accountRepository.findFirstReportTimestamp(activeAccounts)
                                .map(timestamp -> timestamp.toLocalDate())
                                .orElse(today.minusYears(1)));
        model.addAttribute("defaultToDate", today);
        model.addAttribute("request", request);
        return "charts/account-analysis";
    }

    @GetMapping("/data")
    @ResponseBody
    public List<AccountAnalysisChartPoint> getData(
            @RequestParam("from")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam("to")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(name = "account", required = false)
            @Nullable Set<String> accounts) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Дата окончания периода не может быть раньше даты начала");
        }
        ViewFilter filter = ViewFilter.builder()
                .fromDate(from.atStartOfDay(systemDefault()).toInstant())
                .toDate(to.plus(1, ChronoUnit.DAYS).atStartOfDay(systemDefault()).toInstant().minusSeconds(1))
                .accounts(accounts == null ? Collections.emptySet() : accounts)
                .showDetails(false)
                .build();
        return chartService.getChartPoints(filter);
    }
}
