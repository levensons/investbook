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

package ru.investbook.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import ru.investbook.service.AssetsAndCashService;
import ru.investbook.service.InvestmentProportionService;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static java.util.stream.Collectors.toList;

@Controller
@RequestMapping("/charts")
@RequiredArgsConstructor
@Slf4j
public class AccountCompositionController {

    private final InvestmentProportionService investmentProportionService;
    private final AssetsAndCashService assetsAndCashService;

    @GetMapping("/sectors-pie-chart")
    public String getSectorsProportionPage(Model model, HttpServletRequest request) {
        try {
            model.addAttribute("sectorsProportion", getSectorsProportion());
            model.addAttribute("request", request);
            return "charts/sectors-pie-chart";
        } catch (Exception e) {
            model.addAttribute("title", "Ошибка");
            model.addAttribute("message",
                    "При сборке круговой диаграммы секторального состава портфеля возникла ошибка: " + e.getMessage() +
                            ". Полное описание ошибки доступно в файле лога, обратитесь в техническую поддержку.");
            return "success";
        }
    }

    @GetMapping("/sectors-pie-chart/data")
    @ResponseBody
    public Collection<Map<String, ?>> getSectorsProportion() {
        Set<String> accounts = assetsAndCashService.getActiveAccounts();
        Collection<Map<String, ?>> result = investmentProportionService.getSectorsProportion(accounts)
                .entrySet()
                .stream()
                .map(e -> Map.of("sector", e.getKey(), "investment", ((Number) e.getValue()).intValue()))
                .collect(toList());
        int cash = assetsAndCashService.getTotalCashInRub(accounts)
                .map(Number::intValue)
                .orElse(0);
        result.add(Map.of("sector", "Кеш", "investment", cash));
        return result;
    }

    @GetMapping("/securities-pie-chart")
    public String getSecuritiesProportionPage(Model model, HttpServletRequest request) {
        try {
            model.addAttribute("securitiesProportion", getSecuritiesProportion());
            model.addAttribute("request", request);
            return "charts/securities-pie-chart";
        } catch (Exception e) {
            model.addAttribute("title", "Ошибка");
            model.addAttribute("message",
                    "При сборке круговой диаграммы состава портфеля возникла ошибка: " + e.getMessage() +
                            ". Полное описание ошибки доступно в файле лога, обратитесь в техническую поддержку.");
            return "success";
        }
    }

    @GetMapping("/securities-pie-chart/data")
    @ResponseBody
    public Collection<Map<String, ?>> getSecuritiesProportion() {
        Set<String> accounts = assetsAndCashService.getActiveAccounts();
        Collection<Map<String, ?>> result = investmentProportionService.getSecuritiesProportion(accounts)
                .entrySet()
                .stream()
                .map(e -> Map.of("security", e.getKey(), "investment", ((Number) e.getValue()).intValue()))
                .collect(toList());
        int cash = assetsAndCashService.getTotalCashInRub(accounts)
                .map(Number::intValue)
                .orElse(0);
        result.add(Map.of("security", "Кеш", "investment", cash));
        return result;
    }
}
