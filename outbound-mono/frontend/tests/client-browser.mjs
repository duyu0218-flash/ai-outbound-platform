import { expect } from '@playwright/test';
// Runs inside the admin session of browser-acceptance; opens the client portal in its own tab.
export async function clientChecks({ page, origin, check }) {
  const staffFetch = (path, method = 'GET', body) =>
    page.evaluate(
      async ({ path, method, body }) => {
        const response = await fetch(path, {
          method,
          headers: {
            Authorization: 'Bearer ' + sessionStorage.getItem('ai-platform-access-token'),
            'Content-Type': 'application/json',
          },
          body: body === undefined ? undefined : JSON.stringify(body),
        });
        if (!response.ok)
          throw new Error(path + ' ' + response.status + ' ' + (await response.text()));
        return response.json();
      },
      { path, method, body },
    );
  await check('admin sidebar is grouped along the business chain', async () => {
    await page.goto(origin + '/admin');
    const groups = await page.locator('.sidebar .nav-group').allTextContents();
    expect(groups).toEqual([
      '工作台',
      '客户名单',
      '话术与 AI',
      '外呼执行',
      '质检与线索',
      '企业交付',
      '数据分析',
      '组织与权限',
      '系统设置',
    ]);
    await expect(page.getByRole('menuitem').filter({ hasText: '企业租户' })).toBeVisible();
  });
  let password, campaign, call;
  await check('client tenant creation account opening and campaign binding', async () => {
    await page.getByRole('menuitem').filter({ hasText: '企业租户' }).click();
    await expect(page).toHaveURL(origin + '/admin/clients');
    await page.getByRole('button', { name: '新建企业租户', exact: true }).click();
    await page.getByLabel('企业名称', { exact: true }).fill('浏览器企业');
    await page.getByLabel('企业编码', { exact: true }).fill('browser-client');
    await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('企业租户详情')).toBeVisible();
    await page.getByRole('tab', { name: '企业账号', exact: true }).click();
    await page.getByRole('button', { name: '开通账号', exact: true }).click();
    await page
      .getByRole('textbox', { name: '账号用户名', exact: true })
      .fill('browser-client-owner');
    await page.getByRole('textbox', { name: '账号姓名', exact: true }).fill('企业负责人');
    await page.getByRole('button', { name: '开通', exact: true }).click();
    const message = page.locator('.el-message-box__message');
    await expect(message).toContainText('初始密码');
    password = (await message.textContent()).match(/初始密码：(\S+)/)[1];
    await page.locator('.el-message-box__btns button').click();
    await expect(page.getByRole('row').filter({ hasText: 'browser-client-owner' })).toBeVisible();

    const [client] = await staffFetch('/api/v1/ops/clients?keyword=browser-client');
    const campaigns = await staffFetch('/api/v1/campaigns?page=1&size=50');
    for (const c of campaigns) {
      const calls = await staffFetch('/api/v1/ops/calls?campaign_id=' + c.id);
      if (calls.rows.length) {
        campaign = c;
        call = calls.rows[0];
        break;
      }
    }
    expect(call).toBeTruthy();
    await staffFetch(`/api/v1/ops/campaigns/${campaign.id}/client`, 'PUT', {
      client_id: client.id,
    });
    await staffFetch(`/api/v1/calls/${call.id}/analysis`, 'PUT', {
      result_code: 'qualified_lead',
      summary: '浏览器验收：客户确认有意向',
    });
    await page.getByRole('tab', { name: '绑定任务', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: campaign.name })).toBeVisible();
    await page.getByRole('tab', { name: '交付记录', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: '合格线索' })).toBeVisible();
    await page.keyboard.press('Escape');
    await page.getByRole('menuitem').filter({ hasText: '外呼任务' }).click();
    await expect(page.getByRole('row').filter({ hasText: campaign.name })).toContainText(
      '浏览器企业',
    );
  });
  await check('client portal first login password change lead view and follow-up', async () => {
    const portal = await page.context().newPage();
    try {
      await portal.goto(origin + '/client');
      await expect(portal).toHaveURL(origin + '/client/login');
      await portal
        .getByRole('textbox', { name: '用户名', exact: true })
        .fill('browser-client-owner');
      await portal.getByRole('textbox', { name: '密码', exact: true }).fill(password);
      await portal.getByRole('button', { name: '登录', exact: true }).click();
      await expect(portal).toHaveURL(origin + '/client/password');
      await portal.getByRole('textbox', { name: '当前密码', exact: true }).fill(password);
      await portal.getByRole('textbox', { name: '新密码', exact: true }).fill('BrowserClient2026');
      await portal
        .getByRole('textbox', { name: '确认新密码', exact: true })
        .fill('BrowserClient2026');
      await portal.getByRole('button', { name: '保存', exact: true }).click();
      await expect(portal).toHaveURL(origin + '/client');
      // The portal never exposes platform navigation.
      await expect(portal.getByRole('menuitem').filter({ hasText: '外呼任务' })).toHaveCount(0);
      const lead = portal.locator('.el-table__row').filter({ hasText: '浏览器验收' });
      await expect(lead).toBeVisible();
      await expect(lead).toContainText('****');
      await lead.click();
      await expect(portal.getByText('线索详情')).toBeVisible();
      await portal.getByRole('radio', { name: '有效', exact: true }).click({ force: true });
      await portal.getByRole('button', { name: '保存跟进', exact: true }).click();
      await expect(portal.getByText('跟进已保存')).toBeVisible();
      await portal.keyboard.press('Escape');
      await portal.getByRole('menuitem').filter({ hasText: '数据看板' }).click();
      await expect(portal.getByText('按来源任务')).toBeVisible();
      await portal.getByRole('button', { name: '退出', exact: true }).click();
      await expect(portal).toHaveURL(origin + '/client/login');
    } finally {
      await portal.close();
    }
    const [client] = await staffFetch('/api/v1/ops/clients?keyword=browser-client');
    const leads = await staffFetch(`/api/v1/ops/clients/${client.id}/leads`);
    expect(leads.items[0].follow_state).toBe('valid');
  });
}
