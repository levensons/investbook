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

package ru.investbook.report.excel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.investbook.report.DailyPortfolioValueService;
import ru.investbook.report.ForeignExchangeRateService;
import ru.investbook.report.ViewFilter;
import ru.investbook.service.moex.MoexIndexQuote;
import ru.investbook.service.moex.MoexIssClient;
import ru.investbook.web.model.AccountAnalysisChartPoint;
import ru.investbook.web.model.DailyPortfolioValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;

import static ru.investbook.report.ForeignExchangeRateService.RUB;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.ASSETS_RUB;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.DATE;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.INVESTMENT_AMOUNT_USD;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.TOTAL_CASH_USD;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.TOTAL_INVESTMENT_USD;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountAnalysisChartService {

    private static final String IMOEX = "IMOEX";
    private static final String MCFTRR = "MCFTRR";
    private static final int INDEX_LOOKBACK_DAYS = 10;

    private final AccountAnalysisExcelTableView tableView;
    private final DailyPortfolioValueService dailyPortfolioValueService;
    private final ForeignExchangeRateService foreignExchangeRateService;
    private final MoexIssClient moexIssClient;

    /**
     * Builds chart points from the same calculated cells that are used by the Excel report,
     * converting monetary values to RUB for the web charts.
     */
    @Transactional
    public List<AccountAnalysisChartPoint> getChartPoints(ViewFilter filter) {
        ViewFilter.set(filter.toBuilder()
                .showDetails(false)
                .build());
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            List<DailyPortfolioValue> dailyValues = dailyPortfolioValueService.getDailyValues(filter);
            List<MoexIndexQuote> imoexQuotes = getIndexQuotes(filter, IMOEX);
            List<MoexIndexQuote> mcftrrQuotes = getIndexQuotes(filter, MCFTRR);
            Collection<ExcelTable> tables = tableView.createExcelTables();
            if (tables.isEmpty()) {
                return mergeChartPoints(List.of(), dailyValues, imoexQuotes, mcftrrQuotes);
            }

            ExcelTable table = tables.iterator().next();
            table.writeTo(workbook, new CellStyles(workbook));
            if (workbook.getNumberOfSheets() == 0) {
                return mergeChartPoints(List.of(), dailyValues, imoexQuotes, mcftrrQuotes);
            }

            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            BigDecimal usdToRub = foreignExchangeRateService.getExchangeRate("USD", RUB);
            return mergeChartPoints(
                    readChartPoints(sheet, evaluator, usdToRub),
                    dailyValues,
                    imoexQuotes,
                    mcftrrQuotes);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось подготовить данные графиков доходности", e);
        } finally {
            ViewFilter.remove();
        }
    }

    private static List<AccountAnalysisChartPoint> readChartPoints(
            Sheet sheet,
            FormulaEvaluator evaluator,
            BigDecimal usdToRub) {
        List<AccountAnalysisChartPoint> points = new ArrayList<>();
        for (Row row : sheet) {
            @Nullable LocalDate date = getDate(row.getCell(DATE.ordinal()));
            if (date == null) {
                continue;
            }
            points.add(new AccountAnalysisChartPoint(
                    date,
                    getNumber(row.getCell(ASSETS_RUB.ordinal()), evaluator),
                    null,
                    convertUsdToRub(
                            getNumber(row.getCell(TOTAL_INVESTMENT_USD.ordinal()), evaluator),
                            usdToRub),
                    convertUsdToRub(
                            getNumber(row.getCell(INVESTMENT_AMOUNT_USD.ordinal()), evaluator),
                            usdToRub),
                    null,
                    null,
                    null,
                    convertUsdToRub(
                            getNumber(row.getCell(TOTAL_CASH_USD.ordinal()), evaluator),
                            usdToRub)));
        }
        return points;
    }

    private static List<AccountAnalysisChartPoint> mergeChartPoints(
            List<AccountAnalysisChartPoint> excelPoints,
            List<DailyPortfolioValue> dailyValues,
            List<MoexIndexQuote> imoexQuotes,
            List<MoexIndexQuote> mcftrrQuotes) {
        Map<LocalDate, AccountAnalysisChartPoint> excelByDate = excelPoints.stream()
                .collect(toMap(AccountAnalysisChartPoint::date, Function.identity(), (left, _) -> left));
        Map<LocalDate, DailyPortfolioValue> dailyByDate = dailyValues.stream()
                .collect(toMap(DailyPortfolioValue::date, Function.identity(), (left, _) -> left));
        TreeMap<LocalDate, AccountAnalysisChartPoint> result = new TreeMap<>();
        excelByDate.forEach(result::put);
        dailyByDate.forEach((date, daily) -> {
            @Nullable AccountAnalysisChartPoint excel = excelByDate.get(date);
            result.put(date, new AccountAnalysisChartPoint(
                    date,
                    excel == null ? null : excel.assetsRub(),
                    daily.assetsRub(),
                    excel == null ? null : excel.totalInvestmentRub(),
                    excel == null ? null : excel.externalCashFlowRub(),
                    daily.assetsGrowth(),
                    null,
                    null,
                    excel == null ? null : excel.totalCashRub()));
        });
        addIndexGrowth(result, imoexQuotes, IMOEX);
        addIndexGrowth(result, mcftrrQuotes, MCFTRR);
        return List.copyOf(result.values());
    }

    private List<MoexIndexQuote> getIndexQuotes(ViewFilter filter, String indexId) {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate from = LocalDate.ofInstant(filter.getFromDate(), zoneId).minusDays(INDEX_LOOKBACK_DAYS);
        LocalDate to = LocalDate.ofInstant(filter.getToDate(), zoneId);
        try {
            return moexIssClient.getIndexHistory(indexId, from, to);
        } catch (Exception e) {
            log.warn("Не удалось загрузить историю индекса {}", indexId, e);
            return List.of();
        }
    }

    private static void addIndexGrowth(
            TreeMap<LocalDate, AccountAnalysisChartPoint> points,
            List<MoexIndexQuote> quotes,
            String indexId) {
        if (points.isEmpty() || quotes.isEmpty()) {
            return;
        }
        TreeMap<LocalDate, BigDecimal> quotesByDate = new TreeMap<>();
        quotes.forEach(quote -> quotesByDate.put(quote.date(), quote.close()));
        LocalDate firstPortfolioDate = points.values().stream()
                .filter(point -> point.estimatedAssetsGrowth() != null)
                .map(AccountAnalysisChartPoint::date)
                .findFirst()
                .orElse(points.firstKey());
        Map.@Nullable Entry<LocalDate, BigDecimal> baseEntry = quotesByDate.floorEntry(firstPortfolioDate);
        if (baseEntry == null) {
            baseEntry = quotesByDate.ceilingEntry(firstPortfolioDate);
        }
        if (baseEntry == null || baseEntry.getValue().signum() == 0) {
            return;
        }
        LocalDate baseDate = baseEntry.getKey();
        BigDecimal baseValue = baseEntry.getValue();
        points.replaceAll((date, point) -> {
            @Nullable Double growth = null;
            Map.@Nullable Entry<LocalDate, BigDecimal> quote = quotesByDate.floorEntry(date);
            if (!date.isBefore(baseDate) && quote != null) {
                growth = quote.getValue()
                        .divide(baseValue, 20, RoundingMode.HALF_UP)
                        .subtract(BigDecimal.ONE)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
            }
            return new AccountAnalysisChartPoint(
                    point.date(),
                    point.assetsRub(),
                    point.estimatedAssetsRub(),
                    point.totalInvestmentRub(),
                    point.externalCashFlowRub(),
                    point.estimatedAssetsGrowth(),
                    IMOEX.equals(indexId) ? growth : point.imoexGrowth(),
                    MCFTRR.equals(indexId) ? growth : point.mcftrrGrowth(),
                    point.totalCashRub());
        });
    }

    private static @Nullable Double convertUsdToRub(@Nullable Double value, BigDecimal usdToRub) {
        return value == null ? null : BigDecimal.valueOf(value)
                .multiply(usdToRub)
                .doubleValue();
    }

    private static @Nullable LocalDate getDate(@Nullable Cell cell) {
        if (cell == null || cell.getCellType() != CellType.NUMERIC) {
            return null;
        }
        try {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static @Nullable Double getNumber(@Nullable Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        try {
            double value;
            if (cell.getCellType() == CellType.FORMULA) {
                @Nullable CellValue evaluated = evaluator.evaluate(cell);
                if (evaluated == null || evaluated.getCellType() != CellType.NUMERIC) {
                    return null;
                }
                value = evaluated.getNumberValue();
            } else if (cell.getCellType() == CellType.NUMERIC) {
                value = cell.getNumericCellValue();
            } else {
                return null;
            }
            return Double.isFinite(value) ? value : null;
        } catch (Exception ignore) {
            return null;
        }
    }
}
