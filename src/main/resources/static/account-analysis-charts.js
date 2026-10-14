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

(() => {
const chartRoots = []
let requestController
let generation = 0
let defaultFrom, defaultTo
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

function prepareData(points, fields) {
    const source = points.map(point => ({
        ...point,
        dateValue: toTimestamp(point.date)
    }))
    const byTimestamp = new Map(source.map(point => [point.dateValue, point]))
    const data = []
    for (let timestamp = source[0].dateValue;
         timestamp <= source[source.length - 1].dateValue;
         timestamp += DAY_MS) {
        data.push(byTimestamp.get(timestamp) || {
            date: new Date(timestamp).toISOString().slice(0, 10),
            dateValue: timestamp
        })
    }
    for (const field of fields) {
        interpolateField(data, field)
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
    root.locale = {...root.locale, _decimalSeparator: ",", _thousandSeparator: " "}
    root.interfaceColors.set("grid", am5.color(0xe1e7df))
    root.interfaceColors.set("text", am5.color(0x60705f))
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
        dateFormats: {day: "dd.MM", week: "dd.MM", month: "MM.yyyy", year: "yyyy"},
        periodChangeDateFormats: {day: "dd.MM.yyyy", week: "dd.MM.yyyy", month: "MM.yyyy", year: "yyyy"},
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
    chart.get("colors").set("colors", [am5.color(0x367354), am5.color(0x9ea985), am5.color(0xb79060)])
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
    requestController?.abort()
    requestController = new AbortController()
    const signal = requestController.signal
    const currentGeneration = ++generation
    const form = document.getElementById("chart-filter")
    const status = document.getElementById("chart-status")
    const accountInputs = Array.from(form.querySelectorAll("input[name='account']"))
    if (accountInputs.length > 0 && !accountInputs.some(input => input.checked)) {
        status.textContent = "Выберите хотя бы один счёт."
        status.classList.add("error")
        return
    }
    if (form.querySelector("#to-date").value < form.querySelector("#from-date").value) {
        status.textContent = "Дата окончания должна быть не раньше даты начала."
        status.classList.add("error")
        return
    }
    const query = new URLSearchParams(new FormData(form))
    // The visible URL is also the source of truth for reload and browser history.
    history.replaceState(history.state, "", location.pathname + "?" + query.toString())

    status.textContent = "Загружаю данные…"
    status.classList.remove("error")
    try {
        const response = await fetch("/charts/account-analysis/data?" + query.toString(), {signal})
        if (!response.ok) {
            throw new Error("сервер вернул HTTP " + response.status)
        }
        const points = await response.json()
        if (signal.aborted || currentGeneration !== generation || !form.isConnected) return
        disposeCharts()
        if (points.length === 0) {
            status.textContent = "За выбранный период данных нет."
            return
        }

        for (const src of ["index.js", "xy.js", "themes/Animated.js"]) {
            await Investbook.loadScript("https://cdn.amcharts.com/lib/5/" + src)
            if (signal.aborted || currentGeneration !== generation || !form.isConnected) return
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
        const data = prepareData(points, allFields)
        createLineChart("assets-chart", data, assetsSeries, "#,###.00")
        createLineChart("growth-chart", data, growthSeries, "#,###.00", "%")
        const dailyPointCount = points.filter(point => point.estimatedAssetsRub !== null).length
        const imoexPointCount = points.filter(point => point.imoexGrowth !== null).length
        const mcftrrPointCount = points.filter(point => point.mcftrrGrowth !== null).length
        status.textContent = "Расчётных точек: " + dailyPointCount +
            "; IMOEX: " + imoexPointCount +
            "; MCFTRR: " + mcftrrPointCount
    } catch (error) {
        if (signal.aborted || currentGeneration !== generation || !form.isConnected) return
        disposeCharts()
        status.textContent = "Не удалось построить графики: " + error.message
        status.classList.add("error")
    }
}

document.addEventListener("investbook:page-load", () => {
    const form = document.getElementById("chart-filter")
    if (!form) return
    defaultFrom = document.getElementById("from-date").defaultValue
    defaultTo = document.getElementById("to-date").defaultValue
    form.addEventListener("change", event => {
        if (event.target.type === "date") form.querySelectorAll("[data-period]").forEach(button => button.setAttribute("aria-pressed", "false"))
    })
    form.addEventListener("submit", event => { event.preventDefault(); loadCharts() })
    form.querySelectorAll("[data-period]").forEach(button => button.addEventListener("click", () => {
        const to = document.getElementById("to-date")
        const from = document.getElementById("from-date")
        if (button.dataset.period === "all") { from.value = defaultFrom; to.value = defaultTo }
        else {
            const end = new Date((to.value || defaultTo) + "T12:00:00Z")
            const targetMonth = end.getUTCMonth() - (button.dataset.period === "month" ? 1 : 12)
            const day = end.getUTCDate()
            end.setUTCDate(1); end.setUTCMonth(targetMonth)
            const lastDay = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth() + 1, 0)).getUTCDate()
            end.setUTCDate(Math.min(day, lastDay))
            from.value = end.toISOString().slice(0, 10)
        }
        form.querySelectorAll("[data-period]").forEach(b => b.setAttribute("aria-pressed", String(b === button)))
        form.requestSubmit()
    }))
    loadCharts()
})
document.addEventListener("investbook:page-leave", () => {
    generation++
    requestController?.abort()
    disposeCharts()
})
})()
