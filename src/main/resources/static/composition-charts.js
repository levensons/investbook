/* InvestBook composition chart lifecycle. AGPL-3.0-or-later. */
(() => {
    let chart, generation = 0
    const dispose = () => { generation++; chart?.dispose(); chart = null }
    document.addEventListener("investbook:page-leave", dispose)
    document.addEventListener("investbook:page-load", async () => {
        const container = document.getElementById("chartdiv")
        const source = document.getElementById("composition-data")
        const status = document.getElementById("composition-status")
        if (!container || !source || !status) return
        const current = ++generation
        const data = JSON.parse(source.textContent)
        if (!data.some(row => Number(row.investment) > 0)) {
            container.innerHTML = '<div class="chart-empty">Пока нет данных для диаграммы.<br>Загрузите отчёт брокера и котировки.</div>'
            container.style.height = "auto"
            return
        }
        status.textContent = "Загружаю диаграмму…"
        try {
            for (const file of ["core.js", "charts.js"]) {
                await Investbook.loadScript("https://cdn.amcharts.com/lib/4/" + file)
                if (generation !== current || !container.isConnected) return
            }
            chart = am4core.create(container, am4charts.PieChart)
            chart.innerRadius = am4core.percent(62)
            chart.data = data
            const series = chart.series.push(new am4charts.PieSeries())
            series.dataFields.value = "investment"
            series.dataFields.category = container.dataset.category
            series.colors.list = ["#367354", "#8da57c", "#b6c29b", "#c1a47b", "#739c9a", "#a5b5c0"].map(value => am4core.color(value))
            series.slices.template.stroke = am4core.color("#fff")
            series.slices.template.strokeWidth = 3
            series.slices.template.strokeOpacity = 1
            series.labels.template.disabled = true
            series.ticks.template.disabled = true
            chart.legend = new am4charts.Legend()
            chart.legend.position = "right"
            chart.legend.fontSize = 13
            status.textContent = "Состав по последним доступным данным"
        } catch (error) {
            if (generation !== current || !container.isConnected) return
            status.textContent = error.message
            status.classList.add("error")
        }
    })
})()
