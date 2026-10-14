/* InvestBook — shared desktop workspace. AGPL-3.0-or-later. */
(() => {
    "use strict"
    // Only explicitly marked read-only navigation uses Drive. Forms, downloads and actions stay native.
    Turbo.session.drive = false
    Turbo.config.forms.mode = "off"
    const safeRoutes = new Set(["/", "/charts/account-analysis", "/charts/securities-pie-chart",
        "/charts/sectors-pie-chart", "/transactions", "/security-events", "/events", "/account-cash",
        "/account-properties", "/security-quotes", "/security-descriptions", "/security-deposit",
        "/foreign-exchange-rates", "/broker-file-reports", "/broker-email-reports", "/forms.html",
        "/accounts/select-period", "/portfolio-open-format/backup.html"])
    const icons = {
        overview: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
        chart: '<path d="M4 4v16h17M8 14l4-5 4 3 5-7"/>',
        trades: '<path d="M4 8h16l-4-4M20 16H4l4 4"/>',
        income: '<path d="M12 3v12m-4-4 4 4 4-4M4 16v5h16v-5"/>',
        cash: '<rect x="3" y="5" width="18" height="15" rx="3"/><path d="M3 8h18M16 12h5v5h-5z"/>',
        report: '<path d="M6 3h9l4 4v14H6zM14 3v5h5M9 12h7M9 16h7"/>',
        settings: '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2"/><circle cx="15" cy="12" r="2"/><circle cx="8" cy="18" r="2"/>'
    }
    const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${icons[name]}</svg>`
    function shell(body) {
        if (body.querySelector("#app-main")) return
        body.classList.add("app-body")
        const main = document.createElement("main")
        main.id = "app-main"
        main.tabIndex = -1
        while (body.firstChild) main.append(body.firstChild)
        const sidebar = document.createElement("aside")
        sidebar.id = "app-sidebar"
        sidebar.setAttribute("data-turbo-permanent", "")
        sidebar.innerHTML = `
            <a class="app-brand" href="/" data-turbo="true"><span class="brand-mark">ib<span>.</span></span><span>Investbook<small>Финансовая книга</small></span></a>
            <div class="nav-caption">ПОРТФЕЛЬ</div>
            <nav aria-label="Основные разделы">
                <a href="/" data-section="overview">${icon("overview")}Обзор</a>
                <a href="/charts/account-analysis" data-section="analytics">${icon("chart")}Аналитика</a>
                <a href="/transactions" data-section="trades">${icon("trades")}Операции</a>
                <a href="/security-events" data-section="income">${icon("income")}Выплаты</a>
                <a href="/account-cash" data-section="cash">${icon("cash")}Деньги</a>
                <a href="/accounts/select-period" data-section="reports">${icon("report")}Отчёты</a>
            </nav>
            <div class="sidebar-bottom">
                <nav aria-label="Управление"><a href="/forms.html" data-section="settings">${icon("settings")}Данные и настройки</a></nav>
                <a class="help-link" href="/user-guide/index.html">Руководство пользователя →</a>
                <div class="local-status"><span></span>Локальное приложение</div>
            </div>`
        const header = document.createElement("header")
        header.id = "app-header"
        header.setAttribute("data-turbo-permanent", "")
        header.innerHTML = `<div class="workspace-label">МОИ ИНВЕСТИЦИИ<span class="header-divider">/</span><span id="app-section-title"></span></div>
            <a href="/broker-file-reports" class="button primary import-button">＋ Загрузить отчёт</a>`
        const skip = document.createElement("a")
        skip.href = "#app-main"
        skip.className = "skip-link"
        skip.textContent = "К содержимому"
        body.append(skip, sidebar, header, main)
    }
    const readContext = () => {
        try {
            const value = JSON.parse(sessionStorage.getItem("investbook.analysis") || "null")
            return value && typeof value.from === "string" && typeof value.to === "string" &&
                Array.isArray(value.accounts) && value.accounts.every(a => typeof a === "string") ? value : null
        } catch { return null }
    }
    function saveContext(form) {
        const from = form.querySelector("#from-date")?.value
        const to = form.querySelector("#to-date")?.value
        const accounts = [...form.querySelectorAll('input[name="account"]:checked, input[name="accounts"]:checked')].map(el => el.value)
        if (!from || !to || to < from || (form.querySelector('input[type="checkbox"]') && !accounts.length)) return
        try { sessionStorage.setItem("investbook.analysis", JSON.stringify({from, to, accounts})) } catch { /* storage may be disabled */ }
    }
    function restoreContext(form) {
        const query = new URLSearchParams(location.search)
        const saved = readContext()
        const explicit = query.has("from") || query.has("to") || query.has("account")
        const context = explicit ? {from: query.get("from"), to: query.get("to"), accounts: query.getAll("account")} : saved
        if (!context) return
        for (const key of ["from", "to"]) {
            const input = form.querySelector(`#${key}-date`)
            if (input && /^\d{4}-\d{2}-\d{2}$/.test(context[key] || "")) input.value = context[key]
        }
        const checkboxes = [...form.querySelectorAll('input[name="account"], input[name="accounts"]')]
        if (form.id !== "composition-filter" && Array.isArray(context.accounts) && (!explicit || query.has("account"))) {
            checkboxes.forEach(input => input.checked = context.accounts.includes(input.value))
        }
        if (form.id === "get-account" && context.from) window.selectPeriodView?.()
    }
    function syncContextLinks() {
        const saved = readContext()
        if (!saved) return
        document.querySelectorAll('a[href]').forEach(link => {
            const url = new URL(link.href, location.href)
            if (url.origin !== location.origin || !url.pathname.startsWith("/charts/")) return
            // The composition pages use current prices, but carry the selected period back to the history view.
            url.search = ""
            url.searchParams.set("from", saved.from)
            url.searchParams.set("to", saved.to)
            saved.accounts.forEach(account => url.searchParams.append("account", account))
            link.href = url.pathname + url.search + url.hash
        })
    }
    function mount() {
        shell(document.body)
        const path = location.pathname
        const section = path === "/" ? "overview" : path.startsWith("/charts/") ? "analytics" :
            path.startsWith("/transactions") || path.startsWith("/events") ? "trades" :
            path.startsWith("/security-events") ? "income" : path.startsWith("/account-cash") ? "cash" :
            path.startsWith("/accounts/") ? "reports" : "settings"
        document.querySelectorAll("[data-section]").forEach(link => {
            if (link.dataset.section === section) link.setAttribute("aria-current", "page")
            else link.removeAttribute("aria-current")
        })
        document.getElementById("app-section-title").textContent = path === "/" ? "Обзор портфеля" : document.title
        document.querySelectorAll("a[href]").forEach(link => {
            const url = new URL(link.href, location.href)
            if (url.origin === location.origin && safeRoutes.has(url.pathname) && !link.hasAttribute("download")) {
                link.dataset.turbo = "true"
                link.removeAttribute("target")
            }
        })
        document.querySelectorAll('#chart-filter, #get-account, #composition-filter').forEach(form => {
            restoreContext(form)
            form.addEventListener("submit", () => { saveContext(form); syncContextLinks() })
        })
        const composition = document.getElementById("composition-filter")
        composition?.addEventListener("submit", event => {
            event.preventDefault()
            if (composition.querySelector('input[type="checkbox"]') && !composition.querySelector('input[type="checkbox"]:checked')) {
                document.getElementById("composition-status").textContent = "Выберите хотя бы один счёт."
                return
            }
            Turbo.visit(location.pathname + "?" + new URLSearchParams(new FormData(composition)))
        })
        syncContextLinks()
        // Clear redundant legacy "home" links; the persistent menu now supplies this navigation.
        document.querySelectorAll('#app-main > a[href="/"], #app-main > a[href="/forms.html"]').forEach(link => {
            if (/главн/.test(link.textContent)) { if (link.nextElementSibling?.tagName === "BR") link.nextElementSibling.remove(); link.remove() }
        })
        if (document.querySelector("#app-main .search-filter")) {
            document.querySelectorAll("#app-main table").forEach(table => {
                if (table.querySelector("th") && !table.querySelector("td") && !table.nextElementSibling?.classList.contains("table-empty")) {
                    const empty = document.createElement("p")
                    empty.className = "table-empty"
                    empty.textContent = "Записей по выбранным условиям нет. Измените фильтры или загрузите отчёт брокера."
                    table.after(empty)
                }
            })
        }
        document.dispatchEvent(new Event("investbook:page-load"))
    }
    const scripts = new Map()
    function loadScript(src) {
        if (!scripts.has(src)) scripts.set(src, new Promise((resolve, reject) => {
            const script = document.createElement("script")
            const timer = setTimeout(() => fail(), 15000)
            const fail = () => { clearTimeout(timer); script.remove(); scripts.delete(src); reject(new Error("Не удалось загрузить библиотеку графиков. Проверьте соединение.")) }
            script.src = src
            script.onload = () => { clearTimeout(timer); resolve() }
            script.onerror = fail
            document.head.append(script)
        }))
        return scripts.get(src)
    }
    window.Investbook = {loadScript, saveContext, readContext}
    document.addEventListener("turbo:before-render", event => {
        document.dispatchEvent(new Event("investbook:page-leave"))
        shell(event.detail.newBody)
    })
    document.addEventListener("turbo:load", mount)
    document.addEventListener("turbo:before-cache", () => document.dispatchEvent(new Event("investbook:page-leave")))
    window.addEventListener("pagehide", () => document.dispatchEvent(new Event("investbook:page-leave")))
})()
