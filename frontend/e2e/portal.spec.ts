import { expect, test, type Page } from '@playwright/test'

async function login(page: Page, portal: 'admin' | 'agent', username: string) {
  await page.goto(`/${portal}/login`)
  await page.locator('input[autocomplete="username"]').fill(username)
  await page.locator('input[autocomplete="current-password"]').fill('12345678')
  await page.locator('button[type="submit"]').click()
  await expect(page).toHaveURL(new RegExp(`/${portal}/?$`))
}

test('administrator can enter every management route and log out', async ({ page }) => {
  await login(page, 'admin', 'admin')
  for (const route of [
    'contacts',
    'contacts-operations',
    'reports',
    'group-monitor',
    'billing',
    'scripts',
    'campaigns',
    'calls',
    'users',
    'lines',
    'knowledge',
    'settings',
    'system',
  ]) {
    await page.goto(`/admin/${route}`)
    await expect(page.locator('.app-content')).toBeVisible()
    await expect(page.locator('.ant-result-404')).toHaveCount(0)
  }
  await page.locator('.account-button').click()
  await page.locator('.ant-dropdown-menu-item-danger').click()
  await expect(page).toHaveURL(/\/admin\/login$/)
})

test('agent is redirected away from the administrator portal', async ({ page }) => {
  await login(page, 'agent', '1001@test')
  await expect(page.getByText('浏览器软电话', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /启用软电话/ })).toBeDisabled()
  await expect(page.getByText('服务器尚未启用WebRTC', { exact: true })).toBeVisible()
  await expect(page.getByLabel('手机号', { exact: true })).toBeEditable()
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/agent$/)
  await page.goto('/agent/calls')
  await expect(page.locator('.app-content')).toBeVisible()
})

test('recording notice text can be saved and is restored after reload', async ({ page }) => {
  await login(page, 'admin', 'admin')
  await page.goto('/admin/settings')
  await page.getByRole('tab', { name: /合规策略/ }).click()
  const notice = page.getByLabel('录音告知内容', { exact: true })
  await expect(notice).toBeEditable()
  const expected = `本次通话将被录音，用于服务质量管理-${Date.now()}`
  await notice.fill(expected)
  const [response] = await Promise.all([
    page.waitForResponse((candidate) => candidate.url().endsWith('/api/v1/admin/settings/compliance') && candidate.request().method() === 'PUT'),
    page.locator('.ant-tabs-tabpane-active .settings-card button[type="submit"]').click(),
  ])
  expect(response.ok()).toBeTruthy()

  await page.reload()
  await page.getByRole('tab', { name: /合规策略/ }).click()
  await expect(page.getByLabel('录音告知内容', { exact: true })).toHaveValue(expected)
})

test('all administrator menu entries are clickable and return to the dashboard', async ({ page }) => {
  await login(page, 'admin', 'admin')
  const menu = page.locator('.app-sider [role="menuitem"]')
  await expect(menu).toHaveCount(15)
  const count = await menu.count()
  for (let index = 0; index < count; index++) {
    await menu.nth(index).click()
    await expect(page.locator('.app-content')).toBeVisible()
    await expect(page.locator('.page-title-row h2')).toBeVisible()
    await expect(page.locator('.ant-result-404')).toHaveCount(0)
  }
  await page.locator('.brand').click()
  await expect(page).toHaveURL(/\/admin\/?$/)
})

test('contacts can be saved, searched and restored after reload', async ({ page }) => {
  await login(page, 'admin', 'admin')
  await page.goto('/admin/contacts')
  await page.getByRole('button', { name: /新增客户/ }).click()
  const dialog = page.getByRole('dialog')
  const marker = `ReviewContact-${Date.now()}`
  await dialog.getByLabel('手机号', { exact: true }).fill(`139${String(Date.now()).slice(-8)}`)
  await dialog.getByLabel('姓名', { exact: true }).fill(marker)
  await dialog.getByRole('button', { name: /确.*定|OK/ }).click()
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('cell', { name: marker, exact: true })).toBeVisible()
  await page.reload()
  await page.locator('.table-toolbar input').fill(marker)
  await page.getByRole('button', { name: /搜\s*索/ }).click()
  await expect(page.getByRole('cell', { name: marker, exact: true })).toBeVisible()
})

test('invalid login stays unauthenticated and logout protects back navigation', async ({ page }) => {
  await page.goto('/admin/login')
  await page.locator('input[autocomplete="username"]').fill('admin')
  await page.locator('input[autocomplete="current-password"]').fill('synthetic-wrong-password')
  const failure = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/login') && response.request().method() === 'POST')
  await page.locator('button[type="submit"]').click()
  expect((await failure).status()).toBe(401)
  await expect(page).toHaveURL(/\/admin\/login$/)
  await login(page, 'admin', 'admin')
  await page.locator('.app-sider [role="menuitem"]').nth(2).click()
  await page.locator('.account-button').click()
  await page.locator('.ant-dropdown-menu-item-danger').click()
  await expect(page).toHaveURL(/\/admin\/login$/)
  await page.goBack()
  await expect(page.locator('input[autocomplete="username"]')).toBeVisible()
  await expect(page.locator('.app-content')).toHaveCount(0)
})

// Enable only against an explicitly seeded, isolated two-tenant test service.
test('SPA account switch never reuses the previous tenant cache', async ({ page }) => {
  test.skip(!process.env.E2E_REVIEW_TENANTS, 'requires isolated review tenant fixtures')
  await login(page, 'admin', 'admin')
  await page.goto('/admin/contacts')
  await expect(page.getByRole('cell', { name: 'SyntheticTenantA', exact: true })).toBeVisible()
  await page.locator('.account-button').click()
  await page.locator('.ant-dropdown-menu-item-danger').click()
  await expect(page).toHaveURL(/\/admin\/login$/)
  // Do not reload: retain the SPA process where the old cache used to survive.
  await page.locator('input[autocomplete="username"]').fill('review-b')
  await page.locator('input[autocomplete="current-password"]').fill('12345678')
  await page.locator('button[type="submit"]').click()
  await expect(page).toHaveURL(/\/admin\/?$/)
  await page.route('**/api/v1/contacts?**', route => route.abort())
  await page.locator('.app-sider [role="menuitem"]').nth(2).click()
  await expect(page).toHaveURL(/\/admin\/contacts$/)
  await expect(page.getByRole('cell', { name: 'SyntheticTenantA', exact: true })).toHaveCount(0)
  await page.unroute('**/api/v1/contacts?**')
  await page.getByRole('button', { name: /刷新/ }).click()
  await expect(page.getByRole('cell', { name: 'SyntheticTenantB', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'SyntheticTenantA', exact: true })).toHaveCount(0)
})
