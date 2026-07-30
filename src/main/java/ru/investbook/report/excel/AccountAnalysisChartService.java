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
import ru.investbook.report.ViewFilter;
import ru.investbook.web.model.AccountAnalysisChartPoint;
import ru.investbook.web.model.DailyPortfolioValue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static java.util.stream.Collectors.toMap;

import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.ASSETS_GROWTH;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.ASSETS_USD;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.DATE;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.SP500_GROWTH;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.TOTAL_CASH_USD;
import static ru.investbook.report.excel.AccountAnalysisExcelTableHeader.TOTAL_INVESTMENT_USD;

@Service
@RequiredArgsConstructor
public class AccountAnalysisChartService {

    private final AccountAnalysisExcelTableView tableView;
    private final DailyPortfolioValueService dailyPortfolioValueService;

    /**
     * Builds chart points from the same calculated cells that are used by the Excel report.
     * This intentionally keeps the web charts and the exported workbook numerically identical.
     */
    @Transactional
    public List<AccountAnalysisChartPoint> getChartPoints(ViewFilter filter) {
        ViewFilter.set(filter.toBuilder()
                .showDetails(false)
                .build());
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            List<DailyPortfolioValue> dailyValues = dailyPortfolioValueService.getDailyValues(filter);
            Collection<ExcelTable> tables = tableView.createExcelTables();
            if (tables.isEmpty()) {
                return mergeChartPoints(List.of(), dailyValues);
            }

            ExcelTable table = tables.iterator().next();
            table.writeTo(workbook, new CellStyles(workbook));
            if (workbook.getNumberOfSheets() == 0) {
                return mergeChartPoints(List.of(), dailyValues);
            }

            Sheet sheet = workbook.getSheetAt(0);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            return mergeChartPoints(readChartPoints(sheet, evaluator), dailyValues);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось подготовить данные графиков доходности", e);
        } finally {
            ViewFilter.remove();
        }
    }

    private static List<AccountAnalysisChartPoint> readChartPoints(Sheet sheet, FormulaEvaluator evaluator) {
        List<AccountAnalysisChartPoint> points = new ArrayList<>();
        for (Row row : sheet) {
            @Nullable LocalDate date = getDate(row.getCell(DATE.ordinal()));
            if (date == null) {
                continue;
            }
            points.add(new AccountAnalysisChartPoint(
                    date,
                    getNumber(row.getCell(ASSETS_USD.ordinal()), evaluator),
                    null,
                    getNumber(row.getCell(TOTAL_INVESTMENT_USD.ordinal()), evaluator),
                    getNumber(row.getCell(ASSETS_GROWTH.ordinal()), evaluator),
                    null,
                    getNumber(row.getCell(SP500_GROWTH.ordinal()), evaluator),
                    getNumber(row.getCell(TOTAL_CASH_USD.ordinal()), evaluator)));
        }
        return points;
    }

    private static List<AccountAnalysisChartPoint> mergeChartPoints(
            List<AccountAnalysisChartPoint> excelPoints,
            List<DailyPortfolioValue> dailyValues) {
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
                    excel == null ? null : excel.assetsUsd(),
                    daily.assetsUsd(),
                    excel == null ? null : excel.totalInvestmentUsd(),
                    excel == null ? null : excel.assetsGrowth(),
                    daily.assetsGrowth(),
                    excel == null ? null : excel.sp500Growth(),
                    excel == null ? null : excel.totalCashUsd()));
        });
        return List.copyOf(result.values());
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
