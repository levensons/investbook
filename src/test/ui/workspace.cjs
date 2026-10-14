// Browser regression check. Run with Playwright and Chromium installed; see docs/ui-workspace.md.
const {chromium} = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
let browser
;(async () => {
    browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', args: ['--no-sandbox']})
    const context = await browser.newContext({viewport: {width: 1440, height: 1060}, locale: "ru-RU"})
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    const base = process.env.INVESTBOOK_UI_URL || 'http://127.0.0.1:2030'
    // Deterministic chart data; server rendering, navigation and forms still use the running app.
    let chartRequests = []
    let chartPoints = []
    if (process.env.AMCHART_CACHE) {
        await page.route('https://cdn.amcharts.com/lib/**', route => {
            const file = new URL(route.request().url()).pathname.replace('/lib/', '')
            return route.fulfill({path: process.env.AMCHART_CACHE + '/' + file, contentType: 'text/javascript'})
        })
    }
    await page.route('**/charts/account-analysis/data?*', route => {
        chartRequests.push(new URL(route.request().url()))
        return route.fulfill({json: chartPoints})
    })
    await page.goto(base)
    await page.locator('#app-sidebar').waitFor()
    assert.equal(await page.locator('#app-main h1').textContent(), 'Обзор портфеля')
    const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
    await page.evaluate(() => { window.shellIdentity = document.getElementById('app-sidebar'); window.navigationMarker = 'persistent' })
    await fs.mkdir('/tmp/investbook-ui', {recursive: true})
    await page.screenshot({path: '/tmp/investbook-ui/overview.png', fullPage: true})
    await page.getByRole('link', {name: 'Аналитика', exact: true}).click()
    await page.locator('#chart-filter').waitFor()
    await page.waitForFunction(() => document.getElementById('chart-status').textContent.includes('данных нет'))
    assert.equal(await page.evaluate(() => window.navigationMarker), 'persistent', 'Navigation unexpectedly reloaded the document')
    assert.equal(await page.evaluate(() => window.shellIdentity === document.getElementById('app-sidebar')), true)
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), background)
    await page.locator('#from-date').fill('2025-01-01')
    await page.locator('#to-date').fill('2025-03-31')
    await page.getByRole('button', {name: 'Применить', exact: true}).click()
    await page.waitForURL('**/charts/account-analysis?from=2025-01-01&to=2025-03-31')
    await page.screenshot({path: '/tmp/investbook-ui/analytics.png', fullPage: true})
    await page.getByRole('link', {name: 'По бумагам', exact: true}).click()
    await page.waitForURL('**/charts/securities-pie-chart?**')
    await page.locator('#composition-filter').waitFor({state: 'attached'})
    assert.match(page.url(), /from=2025-01-01/)
    assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), background)
    await page.getByRole('link', {name: 'По секторам', exact: true}).click()
    await page.waitForURL('**/charts/sectors-pie-chart?**')
    await page.locator('.menu a[aria-current=page]').filter({hasText: 'По секторам'}).waitFor()
    await page.goBack()
    await page.waitForURL('**/charts/securities-pie-chart?**')
    await page.goBack()
    await page.locator('#chart-filter').waitFor()
    assert.equal(await page.locator('#from-date').inputValue(), '2025-01-01')
    assert.equal(await page.locator('#to-date').inputValue(), '2025-03-31')
    await page.reload()
    await page.locator('#chart-filter').waitFor()
    assert.equal(await page.locator('#from-date').inputValue(), '2025-01-01')
    await page.getByRole('button', {name: 'Месяц', exact: true}).click()
    assert.equal(await page.locator('#from-date').inputValue(), '2025-02-28', 'Month-end quick period must clamp to February')
    await page.getByRole('link', {name: 'Операции', exact: true}).click()
    await page.locator('#app-main h1').filter({hasText: 'Сделки'}).waitFor()
    assert.equal(await page.locator('form.search-filter').getAttribute('method'), 'post')
    await page.getByRole('link', {name: 'Добавить сделку', exact: true}).click()
    await page.waitForURL('**/transactions/edit-form')
    await page.locator('#app-sidebar').waitFor()
    assert.equal(await page.locator('#app-main form').count() > 0, true)
    await page.getByRole('link', {name: 'Данные и настройки', exact: true}).click()
    await page.waitForURL('**/forms.html')
    await page.locator('#app-sidebar').waitFor()
    // Repeated legacy table navigation must not redeclare lexical globals.
    for (let i = 0; i < 2; i++) {
        await page.goto(base + '/security-quotes')
        await page.locator('#app-sidebar').waitFor()
        await page.getByRole('link', {name: 'Обзор', exact: true}).click()
        await page.locator('#portfolio-assets').waitFor()
        await page.goBack()
        await page.waitForURL('**/security-quotes')
    }
    if (process.env.AMCHART_CACHE) {
        chartPoints = [
            {date: '2025-01-01', estimatedAssetsRub: 100000, totalInvestmentRub: 100000, estimatedAssetsGrowth: 0, imoexGrowth: 0, mcftrrGrowth: 0},
            {date: '2025-01-15', estimatedAssetsRub: 102000, totalInvestmentRub: 100000, estimatedAssetsGrowth: 2, imoexGrowth: 1, mcftrrGrowth: 1.2},
            {date: '2025-02-01', estimatedAssetsRub: 124000, totalInvestmentRub: 120000, estimatedAssetsGrowth: 3.6, imoexGrowth: 2, mcftrrGrowth: 2.6},
            {date: '2025-03-31', estimatedAssetsRub: 130000, totalInvestmentRub: 120000, estimatedAssetsGrowth: 8.6, imoexGrowth: 3, mcftrrGrowth: 4}
        ]
        await page.goto(base + '/charts/account-analysis?from=2025-01-01&to=2025-03-31')
        await page.waitForFunction(() => window.am5?.registry.rootElements.length === 2)
        await page.locator('.page-subtitle').evaluate(el => el.textContent = 'Демонстрационные данные для проверки интерфейса')
        await page.screenshot({path: '/tmp/investbook-ui/analytics-with-data.png', fullPage: true})
        await page.getByRole('link', {name: 'Обзор', exact: true}).click()
        await page.locator('#portfolio-assets').waitFor()
        assert.equal(await page.evaluate(() => am5.registry.rootElements.length), 0)
        await page.getByRole('link', {name: 'Аналитика', exact: true}).click()
        await page.waitForFunction(() => window.am5?.registry.rootElements.length === 2)
        await page.getByRole('link', {name: 'Обзор', exact: true}).click()
        await page.locator('#portfolio-assets').waitFor()
        assert.equal(await page.evaluate(() => am5.registry.rootElements.length), 0)
        await page.route('**/charts/securities-pie-chart?*', async route => {
            const response = await route.fetch()
            const html = await response.text()
            const data = [{security: 'Демо: акции', investment: 70000}, {security: 'Демо: облигации', investment: 40000}, {security: 'Демо: деньги', investment: 20000}]
            const body = html.replace(/(<script id="composition-data"[^>]*>)[\s\S]*?(<\/script>)/, '$1' + JSON.stringify(data) + '$2')
            await route.fulfill({response, body})
        })
        await page.getByRole('link', {name: 'Состав активов', exact: false}).click()
        await page.waitForFunction(() => window.am4core?.registry.baseSprites.length === 1)
        await page.locator('.page-subtitle').evaluate(el => el.textContent = 'Демонстрационные данные для проверки интерфейса')
        await page.screenshot({path: '/tmp/investbook-ui/composition-with-data.png', fullPage: true})
        await page.getByRole('link', {name: 'Операции', exact: true}).click()
        await page.locator('form.search-filter').waitFor()
        assert.equal(await page.evaluate(() => am4core.registry.baseSprites.length), 0)
        await page.screenshot({path: '/tmp/investbook-ui/transactions.png', fullPage: true})
    }
    assert.equal(context.pages().length, 1, 'Navigation opened another tab/window')
    assert.ok(chartRequests.length >= 3)
    assert.deepEqual(errors, [], 'Browser JS errors')
    console.log('PASS: shared shell/background, same-document navigation, history, reload, period preservation, month boundaries, native edit form, static settings, no new windows or JS errors')
    await browser.close()
})().catch(error => { console.error(error); process.exitCode = 1 }).finally(() => browser?.close())
