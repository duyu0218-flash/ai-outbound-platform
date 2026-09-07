import { test, expect, type Page } from '@playwright/test'
async function login(page: Page) {
  await page.goto('/admin/login')
  await page.locator('input[autocomplete="username"]').fill('admin')
  await page.locator('input[autocomplete="current-password"]').fill('12345678')
  await page.locator('button[type="submit"]').click()
  await expect(page).toHaveURL(/\/admin\/?$/)
}
test('reviewed lists fetch later server pages and return', async ({ page }) => {
  await login(page)
  for (const [route, resource] of [['contacts', 'contacts'], ['contacts-operations', 'contacts'], ['scripts', 'script-templates'], ['campaigns', 'campaigns']]) {
    const requested: number[] = []
    await page.route(`**/api/v1/${resource}?*`, async (request) => {
      const number = Number(new URL(request.request().url()).searchParams.get('page'))
      requested.push(number)
      await request.fulfill({ json: Array.from({ length: number <= 5 ? 50 : 1 }, (_, index) => ({
        id: (number - 1) * 50 + index + 1, name: `Server-${route}-${number}-${index}`, phone: `1390000${String(index).padStart(4, '0')}`,
        consent_state: 'consented', dnc: false, status: 'draft', mode: 'ai_only', contact_ids: [], retry_limit: 0,
        category: 'test', content: '测试话术', version: 1, is_active: true, tags: '', concurrency: 1,
        created_at: '2026-09-07T00:00:00', updated_at: '2026-09-07T00:00:00',
      })) })
    })
    await page.goto(`/admin/${route}`)
    await expect(page.getByText(`Server-${route}-1-0`, { exact: true })).toBeVisible()
    for (let number = 2; number <= 6; number++) {
      await page.getByRole('button', { name: '下一页', exact: true }).click()
      await expect(page.getByText(`Server-${route}-${number}-0`, { exact: true })).toBeVisible()
    }
    await expect(page.getByRole('button', { name: '下一页', exact: true })).toBeDisabled()
    await page.getByRole('button', { name: '上一页', exact: true }).click()
    await expect(page.getByText(`Server-${route}-5-0`, { exact: true })).toBeVisible()
    expect(requested).toContain(6)
    await page.unroute(`**/api/v1/${resource}?*`)
  }
})
test('prepared campaign supports edit start pause resume stop', async ({ page }) => {
  test.skip(!process.env.E2E_FIX7_ISOLATED, 'isolated mock only')
  const auth = await page.request.post('/api/v1/auth/login', { data: { username: 'admin', password: '12345678' } })
  const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` }
  const suffix = String(Date.now()).slice(-8)
  const contact = await page.request.post('/api/v1/contacts', { headers, data: { phone: `136${suffix}`, name: 'Prepared contact', consent_state: 'consented' } })
  expect(contact.ok()).toBeTruthy()
  const created = await page.request.post('/api/v1/campaigns', { headers, data: { name: `Prepared-${suffix}`, mode: 'ai_only', contact_ids: [(await contact.json()).id], concurrency: 1, retry_limit: 0 } })
  expect(created.ok()).toBeTruthy()
  const campaign = await created.json()
  expect((await page.request.post(`/api/v1/campaigns/${campaign.id}/start?auto_dial=false`, { headers })).ok()).toBeTruthy()
  await login(page)
  await page.goto('/admin/campaigns')
  let row = page.getByRole('row').filter({ hasText: `Prepared-${suffix}` })
  await row.getByRole('button', { name: /编\s*辑/ }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('input').first().fill(`Prepared-edited-${suffix}`)
  await dialog.getByRole('button', { name: /确\s*定|OK/ }).click()
  await expect(dialog).not.toBeVisible()
  await page.reload()
  row = page.getByRole('row').filter({ hasText: `Prepared-edited-${suffix}` })
  await row.getByRole('button', { name: /启\s*动/ }).click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: /确\s*定|OK/ }).click()
  await expect(row.getByRole('button', { name: /暂\s*停/ })).toBeVisible()
  await row.getByRole('button', { name: /暂\s*停/ }).click()
  await row.getByRole('button', { name: /恢\s*复/ }).click()
  await row.getByRole('button', { name: /停\s*止/ }).click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: /确\s*定|OK/ }).click()
  await expect(row.getByRole('button', { name: /启\s*动/ })).toBeVisible()
  await page.screenshot({ path: '../artifacts/fix-p1-p2-20260907/campaign-stopped.png', fullPage: true })
})
test('billing explains missing duration evidence', async ({ page }) => {
  await login(page)
  await page.goto('/admin/billing')
  await expect(page.getByText('费用为按秒折算的估算值，非运营商结算账单')).toBeVisible()
  await expect(page.getByText(/缺失部分未计价/)).toBeVisible()
  await page.screenshot({ path: '../artifacts/fix-p1-p2-20260907/billing.png', fullPage: true })
})
