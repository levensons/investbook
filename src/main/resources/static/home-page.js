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

const PORTFOLIO_STATS_REFRESH_INTERVAL_MS = 5 * 60 * 1000

function formatRubles(value, emptyValue) {
    if (!Number.isFinite(value)) {
        return emptyValue
    }
    return Math.trunc(value).toLocaleString("ru-RU") + " Р"
}

async function refreshPortfolioStats() {
    const assets = document.getElementById("portfolio-assets")
    const cash = document.getElementById("portfolio-cash")
    const status = document.getElementById("portfolio-value-status")
    try {
        const response = await fetch("/accounts/all/stats", {cache: "no-store"})
        if (!response.ok) {
            throw new Error("HTTP " + response.status)
        }
        const stats = await response.json()
        const assetsValue = stats["assets-value"]
        assets.textContent = formatRubles(assetsValue, "--")
        assets.href = Number.isFinite(assetsValue) ?
            "/account-properties" :
            "/account-properties/edit-form/total-assets"
        cash.textContent = formatRubles(stats["cash-balance"], "---")
        const quotesUpdatedAt = stats["quotes-updated-at"]
        status.textContent = quotesUpdatedAt ?
            "Текущие биржевые котировки по состоянию на " +
                new Date(quotesUpdatedAt).toLocaleString("ru-RU", {
                    day: "2-digit",
                    month: "2-digit",
                    hour: "2-digit",
                    minute: "2-digit"
                }) :
            "Используются последние доступные котировки; live-обновление выполняется каждый час"
    } catch (error) {
        status.textContent = "Не удалось обновить стоимость: " + error.message
    }
}

refreshPortfolioStats()
setInterval(refreshPortfolioStats, PORTFOLIO_STATS_REFRESH_INTERVAL_MS)

document.addEventListener("visibilitychange", () => {
    if (!document.hidden) {
        refreshPortfolioStats()
    }
})
