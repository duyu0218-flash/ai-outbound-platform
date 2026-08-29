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
  for (const route of ['contacts', 'scripts', 'campaigns', 'calls', 'users', 'lines', 'knowledge', 'settings', 'system']) {
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
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/agent$/)
  await page.goto('/agent/calls')
  await expect(page.locator('.app-content')).toBeVisible()
})
