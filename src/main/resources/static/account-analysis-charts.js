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

function disposeCharts() {
    while (chartRoots.length > 0) {
        chartRoots.pop().dispose()
    }
}

function prepareData(points) {
    return points.map(point => ({
        ...point,
        dateValue: new Date(point.date + "T00:00:00").getTime()
    }))
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
        wheelY: "none",
        pinchZoomX: true,
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

    for (const definition of seriesDefinitions) {
        const series = chart.series.push(am5xy.LineSeries.new(root, {
            name: definition.name,
            xAxis: xAxis,
            yAxis: yAxis,
            valueXField: "dateValue",
            valueYField: definition.field,
            connect: true,
            tooltip: am5.Tooltip.new(root, {
                labelText: "{name}: {valueY.formatNumber('" + numberFormat + "')}" + valueSuffix
            })
        }))
        series.strokes.template.setAll({strokeWidth: 2})
        series.data.setAll(data.filter(point => point[definition.field] !== null))
    }

    const legend = chart.children.push(am5.Legend.new(root, {
        centerX: am5.percent(50),
        x: am5.percent(50)
    }))
    legend.data.setAll(chart.series.values)

    const cursor = chart.set("cursor", am5xy.XYCursor.new(root, {
        xAxis: xAxis
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

        const data = prepareData(points)
        createLineChart("assets-chart", data, [
            {name: "Расчётная стоимость", field: "estimatedAssetsUsd"},
            {name: "Оценка брокера", field: "assetsUsd"},
            {name: "Чистые пополнения", field: "totalInvestmentUsd"}
        ], "#,###.00")
        createLineChart("growth-chart", data, [
            {name: "Портфель (расчёт)", field: "estimatedAssetsGrowth"},
            {name: "Портфель (оценки брокера)", field: "assetsGrowth"},
            {name: "S&P 500", field: "sp500Growth"}
        ], "#,###.00", "%")
        createLineChart("cash-chart", data, [
            {name: "Денежные средства", field: "totalCashUsd"}
        ], "#,###.00")
        const brokerPointCount = points.filter(point => point.assetsUsd !== null).length
        const dailyPointCount = points.filter(point => point.estimatedAssetsUsd !== null).length
        status.textContent = "Расчётных точек: " + dailyPointCount +
            "; оценок из брокерских отчётов: " + brokerPointCount
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
