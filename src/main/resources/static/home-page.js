/* InvestBook overview lifecycle. AGPL-3.0-or-later. */
(() => {
    let timer, controller
    const stop = () => { clearInterval(timer); controller?.abort() }
    async function refresh() {
        controller?.abort()
        controller = new AbortController()
        const signal = controller.signal
        const assets = document.getElementById("portfolio-assets")
        const cash = document.getElementById("portfolio-cash")
        const status = document.getElementById("portfolio-value-status")
        if (!assets || !cash || !status) return
        const format = value => Number.isFinite(value) ? Math.trunc(value).toLocaleString("ru-RU") + " ₽" : "Нет данных"
        try {
            const response = await fetch("/accounts/all/stats", {cache: "no-store", signal})
            if (!response.ok) throw new Error("HTTP " + response.status)
            const stats = await response.json()
            if (signal.aborted || !assets.isConnected) return
            assets.textContent = format(stats["assets-value"])
            cash.textContent = format(stats["cash-balance"])
            status.textContent = Number.isFinite(stats["assets-value"]) ? "По сохранённым котировкам и остаткам" : "Для оценки нужны котировки и отчёт брокера"
        } catch (error) {
            if (!signal.aborted && status.isConnected) status.textContent = "Не удалось обновить оценку. Попробуйте позже."
        }
    }
    document.addEventListener("investbook:page-load", () => {
        stop()
        if (!document.getElementById("portfolio-assets")) return
        refresh()
        timer = setInterval(refresh, 300000)
    })
    document.addEventListener("investbook:page-leave", stop)
    document.addEventListener("visibilitychange", () => { if (!document.hidden && document.getElementById("portfolio-assets")) refresh() })
})()
