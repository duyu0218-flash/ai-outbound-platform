import { expect, test } from '@playwright/test'

// Dedicated Java fixture: JAVA_DISPATCH_ENABLED=true, JAVA_WORKERS_ENABLED=true,
// TELEPHONY_PROVIDER=mock. All numbers and records below are synthetic.
test('Java mock dispatch reaches completed history, analysis and reports', async ({ page }) => {
  test.skip(process.env.E2E_JAVA_DISPATCH !== '1', 'requires isolated Java mock worker fixture')
  const login = await page.request.post('/api/v1/auth/login', { data: { username: 'admin', password: '12345678' } })
  expect(login.ok()).toBeTruthy()
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const compliance = (await (await page.request.get('/api/v1/admin/settings/compliance', { headers })).json()).data
  expect((await page.request.put('/api/v1/admin/settings/compliance', { headers, data: { data: { ...compliance, allowed_start_hour: 0, allowed_end_hour: 0 } } })).ok()).toBeTruthy()
  const marker = `JavaMock-${Date.now()}`
  const phone = `139${String(Date.now()).slice(-8)}`
  const contactResponse = await page.request.post('/api/v1/contacts', { headers, data: { name: marker, phone, consent_state: 'consented' } })
  expect(contactResponse.ok()).toBeTruthy()
  const templateResponse = await page.request.post('/api/v1/script-templates', { headers, data: { name: marker, content: '合成外呼验收话术' } })
  expect(templateResponse.ok()).toBeTruthy()
  const campaignResponse = await page.request.post('/api/v1/campaigns', { headers, data: { name: marker, mode: 'human_only', concurrency: 1, retry_limit: 0, contact_ids: [(await contactResponse.json()).id], script_template_id: (await templateResponse.json()).id } })
  expect(campaignResponse.ok()).toBeTruthy()
  const campaign = await campaignResponse.json()
  await page.goto('/admin/login')
  await page.locator('input[autocomplete="username"]').fill('admin')
  await page.locator('input[autocomplete="current-password"]').fill('12345678')
  await page.locator('button[type="submit"]').click()
  await expect(page).toHaveURL(/\/admin\/?$/)
  await page.getByRole('menuitem', { name: /外呼任务/ }).click()
  const row = page.getByRole('row').filter({ hasText: marker })
  await expect(row).toBeVisible()
  const started = page.waitForResponse(r => r.url().includes(`/campaigns/${campaign.id}/start`) && r.request().method() === 'POST')
  await row.getByRole('button', { name: /启\s*动/ }).click()
  await page.locator('.ant-popconfirm').getByRole('button', { name: /确.*定|OK/ }).click()
  expect((await started).ok()).toBeTruthy()
  await expect.poll(async () => (await (await page.request.get(`/api/v1/campaigns/${campaign.id}`, { headers })).json()).status, { timeout: 20000 }).toBe('completed')
  await page.getByRole('menuitem', { name: /通话记录/ }).click()
  const callRow = page.getByRole('row').filter({ hasText: phone })
  await expect(callRow).toBeVisible()
  await callRow.getByRole('button', { name: /事\s*件/ }).click()
  await expect(page.locator('.ant-drawer')).toBeVisible()
  await page.locator('.ant-drawer-close').click()
  const calls = await (await page.request.get(`/api/v1/calls?campaign_id=${campaign.id}`, { headers })).json()
  const call = calls.find((c: { campaign_id: number }) => c.campaign_id === campaign.id)
  expect(call.status).toBe('completed')
  await expect.poll(async () => (await page.request.get(`/api/v1/calls/${call.id}/analysis`, { headers })).status()).toBe(200)
  for (const route of ['reports', 'billing', 'group-monitor']) {
    const response = page.waitForResponse(r => r.url().includes(`/api/v1/admin/${route === 'reports' ? 'call-reports' : route === 'group-monitor' ? 'contact-groups' : route}`) && r.request().method() === 'GET')
    await page.goto(`/admin/${route}`)
    expect((await response).ok()).toBeTruthy()
    await expect(page.locator('.page-title-row h2')).toBeVisible()
  }
  await page.locator('.brand').click()
  await expect(page).toHaveURL(/\/admin\/?$/)
  await page.screenshot({ path: test.info().outputPath('java-mock-completed.png'), fullPage: true })
})
