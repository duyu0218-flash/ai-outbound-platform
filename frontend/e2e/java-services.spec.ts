import { expect, test } from '@playwright/test'
import { createHmac } from 'node:crypto'

test('Java service stack campaign dispatch, signed completion, history and analysis', async ({ page }) => {
  test.skip(process.env.E2E_JAVA_SERVICES !== '1', 'requires the disposable all-Java fixture')
  const login = await page.request.post('/api/v1/auth/login', { data: { username: 'admin', password: '12345678' } })
  expect(login.ok()).toBeTruthy()
  const headers = { Authorization: `Bearer ${(await login.json()).access_token}` }
  const marker = `JavaServices-${Date.now()}`
  const phone = `139${String(Date.now()).slice(-8)}`
  const contactResponse = await page.request.post('/api/v1/contacts', { headers, data: { name: marker, phone, consent_state: 'consented' } })
  expect(contactResponse.ok()).toBeTruthy()
  const campaignResponse = await page.request.post('/api/v1/campaigns', { headers, data: { name: marker, mode: 'ai_only', concurrency: 1, retry_limit: 0, contact_ids: [(await contactResponse.json()).id] } })
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
  let callId = ''
  await expect.poll(async () => {
    const calls = await (await page.request.get(`/api/v1/calls?campaign_id=${campaign.id}`, { headers })).json()
    const call = calls.find((c: { campaign_id: number }) => c.campaign_id === campaign.id)
    if (call) callId = call.id
    return call?.status
  }).toBe('in_ai')
  const body = JSON.stringify({ call_id: callId, kind: 'status', payload: { status: 'completed', attempt: 1, event_id: marker, duration_sec: 8 } })
  const stamp = String(Math.floor(Date.now() / 1000))
  const completed = await page.request.post('/api/v1/webhooks/telephony/status', {
    data: body, headers: { 'Content-Type': 'application/json', 'x-webhook-token': process.env.E2E_WEBHOOK_TOKEN || '',
      'x-webhook-timestamp': stamp, 'x-webhook-signature': createHmac('sha256', process.env.E2E_WEBHOOK_SECRET || '').update(stamp + '.' + body).digest('hex') },
  })
  expect(completed.ok()).toBeTruthy()
  await expect.poll(async () => (await (await page.request.get(`/api/v1/calls/${callId}`, { headers })).json()).status).toBe('completed')
  await expect.poll(async () => (await page.request.get(`/api/v1/calls/${callId}/analysis`, { headers })).status()).toBe(200)
  await page.getByRole('menuitem', { name: /通话记录/ }).click()
  const callRow = page.getByRole('row').filter({ hasText: phone })
  await expect(callRow).toBeVisible()
  await callRow.getByRole('button', { name: /事\s*件/ }).click()
  await expect(page.locator('.ant-drawer')).toBeVisible()
  await page.locator('.ant-drawer-close').click()
  await page.goto('/admin/reports')
  await expect(page.locator('.page-title-row h2')).toBeVisible()
  await page.locator('.brand').click()
  await expect(page).toHaveURL(/\/admin\/?$/)
})
