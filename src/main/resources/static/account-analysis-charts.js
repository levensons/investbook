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

const chartRoots = []
const DAY_MS = 24 * 60 * 60 * 1000
const DEPOSIT_COLOR = 0x2e7d32
const WITHDRAWAL_COLOR = 0xc62828

function disposeCharts() {
    while (chartRoots.length > 0) {
        chartRoots.pop().dispose()
    }
}

function toTimestamp(date) {
    const [year, month, day] = date.split("-").map(Number)
    return Date.UTC(year, month - 1, day)
}

function prepareData(points, fields, fieldsToExtend = [], endDate = null) {
    const source = points.map(point => ({
        ...point,
        dateValue: toTimestamp(point.date)
    }))
    const byTimestamp = new Map(source.map(point => [point.dateValue, point]))
    const lastSourceTimestamp = source[source.length - 1].dateValue
    const endTimestamp = endDate === null ?
        lastSourceTimestamp : Math.max(lastSourceTimestamp, toTimestamp(endDate))
    const data = []
    for (let timestamp = source[0].dateValue;
         timestamp <= endTimestamp;
         timestamp += DAY_MS) {
        data.push(byTimestamp.get(timestamp) || {
            date: new Date(timestamp).toISOString().slice(0, 10),
            dateValue: timestamp
        })
    }
    for (const field of fields) {
        interpolateField(data, field)
    }
    for (const field of fieldsToExtend) {
        extendFieldToEnd(data, field)
    }
    return data
}

function interpolateField(data, field) {
    let previousIndex = -1
    for (let index = 0; index < data.length; index++) {
        if (!Number.isFinite(data[index][field])) {
            continue
        }
        if (previousIndex >= 0 && index - previousIndex > 1) {
            const previous = data[previousIndex]
            const next = data[index]
            const timeRange = next.dateValue - previous.dateValue
            for (let missingIndex = previousIndex + 1; missingIndex < index; missingIndex++) {
                const point = data[missingIndex]
                const position = (point.dateValue - previous.dateValue) / timeRange
                point[field] = previous[field] + (next[field] - previous[field]) * position
                point[field + "Interpolated"] = true
            }
        }
        previousIndex = index
    }
}

function extendFieldToEnd(data, field) {
    let lastValue
    for (const point of data) {
        if (Number.isFinite(point[field])) {
            lastValue = point[field]
        } else if (Number.isFinite(lastValue)) {
            point[field] = lastValue
        }
    }
}

function addCashFlowBullets(root, series) {
    series.bullets.push((bulletRoot, _, dataItem) => {
        const point = dataItem.dataContext
        const cashFlow = point.externalCashFlowRub
        if (!Number.isFinite(cashFlow) || cashFlow === 0) {
            return
        }
        const isDeposit = cashFlow > 0
        const color = am5.color(isDeposit ? DEPOSIT_COLOR : WITHDRAWAL_COLOR)
        const marker = am5.Triangle.new(bulletRoot, {
            width: 14,
            height: 12,
            rotation: isDeposit ? 0 : 180,
            centerX: am5.percent(50),
            centerY: am5.percent(50),
            dy: isDeposit ? -9 : 9,
            fill: color,
            stroke: am5.color(0xffffff),
            strokeWidth: 1.5,
            tooltipText: (isDeposit ? "Пополнение: +" : "Снятие: ") +
                "{externalCashFlowRub.formatNumber('#,###.00')} ₽"
        })
        marker.set("tooltip", am5.Tooltip.new(bulletRoot, {}))
        return am5.Bullet.new(bulletRoot, {
            sprite: marker
        })
    })
}

function formatTooltipDate(date) {
    const [year, month, day] = date.split("-")
    return day + "." + month + "." + year
}

function createCombinedTooltip(root, series, seriesDefinitions, numberFormat, valueSuffix) {
    const tooltip = am5.Tooltip.new(root, {})
    tooltip.label.adapters.add("text", () => {
        const dataItem = series.get("tooltipDataItem")
        const point = dataItem && dataItem.dataContext
        if (!point) {
            return ""
        }
        const lines = ["[bold]" + formatTooltipDate(point.date) + "[/]"]
        for (const definition of seriesDefinitions) {
            const value = point[definition.field]
            if (Number.isFinite(value)) {
                lines.push(
                    definition.name + ": " +
                    root.numberFormatter.format(value, numberFormat) +
                    valueSuffix)
            }
        }
        return lines.join("\n")
    })
    return tooltip
}

function createLineChart(elementId, data, seriesDefinitions, numberFormat, valueSuffix = "") {
    const root = am5.Root.new(elementId)
    chartRoots.push(root)
    root.setThemes([am5themes_Animated.new(root)])
    root.dateFormatter.setAll({
        dateFormat: "dd.MM.yyyy",
        dateFields: ["valueX"]
    })

    const chart = root.container.children.push(am5xy.XYChart.new(root, {
        panX: true,
        panY: false,
        wheelX: "panX",
        wheelY: "zoomX",
        pinchZoomX: true,
        maxTooltipDistance: 0,
        arrangeTooltips: true,
        layout: root.verticalLayout
    }))

    const xAxis = chart.xAxes.push(am5xy.DateAxis.new(root, {
        baseInterval: {timeUnit: "day", count: 1},
        renderer: am5xy.AxisRendererX.new(root, {
            minGridDistance: 70
        }),
        tooltip: am5.Tooltip.new(root, {})
    }))

    const yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, {
        numberFormat: numberFormat + (valueSuffix ? "'" + valueSuffix + "'" : ""),
        renderer: am5xy.AxisRendererY.new(root, {})
    }))

    const lineSeries = []
    for (const definition of seriesDefinitions) {
        const series = chart.series.push(am5xy.LineSeries.new(root, {
            name: definition.name,
            xAxis: xAxis,
            yAxis: yAxis,
            valueXField: "dateValue",
            valueYField: definition.field,
            connect: true,
            snapTooltip: true
        }))
        series.set(
            "tooltip",
            createCombinedTooltip(
                root,
                series,
                seriesDefinitions,
                numberFormat,
                valueSuffix))
        series.strokes.template.setAll({strokeWidth: 2})
        if (definition.cashFlowMarkers) {
            addCashFlowBullets(root, series)
        }
        series.data.setAll(data)
        lineSeries.push(series)
    }

    const legend = chart.children.push(am5.Legend.new(root, {
        centerX: am5.percent(50),
        x: am5.percent(50)
    }))
    legend.data.setAll(chart.series.values)

    const cursor = chart.set("cursor", am5xy.XYCursor.new(root, {
        xAxis: xAxis,
        snapToSeries: lineSeries,
        snapToSeriesBy: "x!"
    }))
    cursor.lineY.set("visible", false)

    chart.set("scrollbarX", am5.Scrollbar.new(root, {
        orientation: "horizontal"
    }))
    chart.appear(600, 50)
}

async function loadCharts() {
    const form = document.getElementById("chart-filter")
    const status = document.getElementById("chart-status")
    const accountInputs = Array.from(form.querySelectorAll("input[name='account']"))
    if (accountInputs.length > 0 && !accountInputs.some(input => input.checked)) {
        status.textContent = "Выберите хотя бы один счёт."
        status.classList.add("error")
        return
    }
    const query = new URLSearchParams(new FormData(form))

    status.textContent = "Загружаю данные…"
    status.classList.remove("error")
    try {
        const response = await fetch("/charts/account-analysis/data?" + query.toString())
        if (!response.ok) {
            throw new Error("сервер вернул HTTP " + response.status)
        }
        const points = await response.json()
        disposeCharts()
        if (points.length === 0) {
            status.textContent = "За выбранный период данных нет."
            return
        }

        const assetsSeries = [
            {name: "Расчётная стоимость", field: "estimatedAssetsRub", cashFlowMarkers: true},
            {name: "Чистые пополнения", field: "totalInvestmentRub"}
        ]
        const growthSeries = [
            {name: "Индекс портфеля", field: "estimatedAssetsGrowth"},
            {name: "IMOEX", field: "imoexGrowth"},
            {name: "MCFTRR", field: "mcftrrGrowth"}
        ]
        const allFields = [...assetsSeries, ...growthSeries]
            .map(definition => definition.field)
        const data = prepareData(
            points,
            allFields,
            ["totalInvestmentRub"],
            query.get("to"))
        createLineChart("assets-chart", data, assetsSeries, "#,###.00")
        createLineChart("growth-chart", data, growthSeries, "#,###.00", "%")
        const dailyPointCount = points.filter(point => point.estimatedAssetsRub !== null).length
        const imoexPointCount = points.filter(point => point.imoexGrowth !== null).length
        const mcftrrPointCount = points.filter(point => point.mcftrrGrowth !== null).length
        status.textContent = "Расчётных точек: " + dailyPointCount +
            "; IMOEX: " + imoexPointCount +
            "; MCFTRR: " + mcftrrPointCount
    } catch (error) {
        disposeCharts()
        status.textContent = "Не удалось построить графики: " + error.message
        status.classList.add("error")
    }
}

document.getElementById("chart-filter").addEventListener("submit", event => {
    event.preventDefault()
    loadCharts()
})

loadCharts()
