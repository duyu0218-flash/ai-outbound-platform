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
