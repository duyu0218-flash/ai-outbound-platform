import { expect } from '@playwright/test';
export async function competitiveChecks({ page, check, go }) {
  await check('round two frequency and evaluation dialogs save real rules', async () => {
    await go('/admin/tasks/setting', '任务设置');
    await page.getByRole('tab', { name: '呼频规则', exact: true }).click();
    await page.getByRole('button', { name: '新建规则', exact: true }).click();
    await page.getByRole('dialog').getByLabel('规则名称', { exact: true }).fill('浏览器48小时规则');
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: '浏览器48小时规则' })).toBeVisible();
    await page.getByRole('tab', { name: '智能评估', exact: true }).click();
    await page.getByRole('button', { name: '创建规则', exact: true }).click();
    await page.getByRole('dialog').getByLabel('规则名称', { exact: true }).fill('浏览器三档评估');
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click();
    await page.reload();
    await page.getByRole('tab', { name: '智能评估', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: '浏览器三档评估' })).toBeVisible();
    await page.getByRole('tab', { name: '话单推送', exact: true }).click();
    await expect(page.getByLabel('calls推送地址', { exact: true })).toBeVisible();
    await page.getByRole('tab', { name: '欠费推送', exact: true }).click();
    await expect(
      page.getByRole('combobox', { name: 'balance解密算法', exact: true }),
    ).toBeVisible();
  });
  await check(
    'round two custom export and account security expose working configuration',
    async () => {
      await go('/admin/tasks/record', '话单查询');
      await page.getByRole('tab', { name: '数据导出', exact: true }).click();
      await expect(page.getByRole('button', { name: '自定义导出', exact: true })).toBeVisible();
      await expect(
        page.getByRole('combobox', { name: '导出对话内容范围', exact: true }),
      ).toBeVisible();
      await expect(page.getByLabel('导出批量号码文件', { exact: true })).toBeVisible();
      await go('/admin/account/settings', '个人设置');
      await page.getByRole('tab', { name: '安全设置', exact: true }).click();
      await expect(page.getByLabel('安全IP地址列表', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: '开启双因子验证', exact: true })).toBeVisible();
    },
  );
  await check('round two enterprise WeChat pool saves and survives reload', async () => {
    await go('/admin/operations/wecom', '企微添加');
    await page.getByRole('tab', { name: '搜索企微', exact: true }).click();
    await page.getByRole('button', { name: '添加微信号', exact: true }).click();
    await page.getByRole('dialog').getByLabel('微信号', { exact: true }).fill('13100008888');
    await page
      .getByRole('dialog')
      .getByLabel('企微配置名称', { exact: true })
      .fill('浏览器服务微信');
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '保存企微添加配置', exact: true })
      .click();
    await expect(page.getByRole('row').filter({ hasText: '浏览器服务微信' })).toBeVisible();
    await page.reload();
    await page.getByRole('tab', { name: '搜索企微', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: '浏览器服务微信' })).toBeVisible();
  });
  await check('enterprise WeChat history paginates and status filters reset the page', async () => {
    await go('/admin/operations/wecom', '企微添加');
    await page.getByRole('tab', { name: '企微配置', exact: true }).click();
    const history = page
      .locator('.el-card')
      .filter({ has: page.getByRole('heading', { name: '自动添加请求', exact: true }) });
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-00' })).toBeVisible();
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-' })).toHaveCount(20);
    await history.locator('.el-pagination .btn-next').click();
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-24' })).toBeVisible();
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-' })).toHaveCount(5);
    await page
      .getByRole('combobox', { name: '请求状态', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: '服务商已受理', exact: true }).click();
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-' })).toHaveCount(13);
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-00' })).toBeVisible();
    await expect(history.locator('.el-pagination .btn-prev')).toBeDisabled();
    await page.reload();
    await page.getByRole('tab', { name: '企微配置', exact: true }).click();
    await expect(history.getByRole('row').filter({ hasText: 'browser-request-00' })).toBeVisible();
  });
  await check('round two compact creation keeps creation and dialing separate', async () => {
    await go('/admin/campaigns', '外呼任务');
    await page.getByRole('button', { name: '创建任务', exact: true }).click();
    const compact = page.getByRole('switch', { name: '使用快捷创建', exact: true });
    if (!(await compact.isChecked())) await compact.locator('..').click();
    await expect(page.getByRole('button', { name: '只创建不外呼', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '确认外呼', exact: true })).toBeVisible();
    await page.getByLabel('任务名称', { exact: true }).fill('浏览器快捷创建任务');
    const script = page.getByRole('combobox', { name: '已发布话术', exact: true });
    await script.locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]').click();
    await page.getByRole('option', { name: 'Vue浏览器话术 · v1', exact: true }).click();
    const field = page
      .getByRole('dialog')
      .locator('.el-form-item')
      .filter({ has: page.getByText('补充选择联系人', { exact: true }) });
    await field.getByRole('combobox').click();
    await page.getByRole('option').filter({ hasText: '13100000002' }).click();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '只创建不外呼', exact: true }).click();
    await expect(
      page.locator('.el-table__row').filter({ hasText: '浏览器快捷创建任务' }),
    ).toContainText('草稿');
    await page.locator('.el-drawer__close-btn').click();
  });
  await check('third round group pacing dialog and contextual help are reachable', async () => {
    await go('/admin/account/seatingMon', '坐席监控');
    await page.getByRole('button', { name: '倍速修改', exact: true }).click();
    await expect(
      page.getByRole('dialog').getByRole('combobox', { name: '倍速坐席组', exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('dialog').getByRole('spinbutton', { name: '坐席组倍速值', exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('dialog').getByRole('spinbutton', { name: '倍速平均等待时长', exact: true }),
    ).toBeVisible();
    await page.getByRole('dialog').locator('button.el-dialog__headerbtn').click();
    const header = page.locator('.app-header');
    for (const name of ['个性化单呼', '个性化群呼', '纯机器人外呼', '使用说明'])
      await expect(header.getByRole('button', { name, exact: true })).toHaveCount(0);
    await header.getByRole('button', { name: '帮助', exact: true }).click();
    await expect(page.getByRole('tab', { name: '纯机器人外呼', exact: true })).toBeVisible();
    await page.locator('.el-drawer__close-btn').click();
    await go('/admin/dashBoard/intercept', '拦截统计');
    await expect(page.getByText(/呼前拦截数/)).toBeVisible();
  });
  await check('competitor page URLs reload through authenticated Vue routes', async () => {
    const origin = new URL(page.url()).origin;
    await page.goto(origin + '/tasks/current');
    await expect(page).toHaveURL(origin + '/admin/tasks/current');
    await expect(page.getByRole('heading', { name: '当前任务', exact: true })).toBeVisible();
  });
  await check('current task create action opens the task wizard directly', async () => {
    await go('/admin/tasks/current', '当前任务');
    await page.getByRole('button', { name: '创建任务', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByLabel('任务名称', { exact: true })).toBeVisible();
    await page.getByRole('dialog').locator('button.el-dialog__headerbtn').click();
  });
  await check('competitor task settings save reload pause windows and restore', async () => {
    await go('/admin/tasks/setting', '任务设置');
    await page.getByRole('button', { name: '添加暂停时间段', exact: true }).click();
    await page.getByLabel('暂停时间段开始0', { exact: true }).fill('12:00');
    await page.getByLabel('暂停时间段结束0', { exact: true }).fill('13:00');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.reload();
    await expect(page.getByLabel('暂停时间段开始0', { exact: true })).toHaveValue('12:00');
    await page.getByRole('tab', { name: '呼频规则', exact: true }).click();
    await expect(page.getByLabel('每日拨打次数', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '恢复', exact: true }).click();
    await expect(page.getByLabel('暂停时间段开始0', { exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await page.getByRole('tab', { name: '外呼设置', exact: true }).click();
    await expect(page.getByLabel('暂停时间段开始0', { exact: true })).toHaveCount(0);
  });
  await check('filter group persists rule and can be switched off', async () => {
    await go('/admin/account/filterStrategy', '过滤策略');
    await page.getByRole('button', { name: '新建策略组', exact: true }).click();
    await page.getByLabel('配置名称', { exact: true }).fill('浏览器前缀策略');
    await page.getByRole('button', { name: '添加规则', exact: true }).click();
    await page.getByLabel('过滤规则值', { exact: true }).fill('13900000000');
    await page.getByRole('button', { name: '保存配置', exact: true }).click();
    const row = page.getByRole('row').filter({ hasText: '浏览器前缀策略' });
    await expect(row).toBeVisible();
    await row.locator('.el-switch').click();
    await expect(row.getByRole('switch')).not.toBeChecked();
    await page.reload();
    await expect(row.getByRole('switch')).not.toBeChecked();
  });
  await check('dataset selects numbers and opens task wizard with selected contacts', async () => {
    await go('/admin/dashBoard/datacube', '数据集');
    await page.getByLabel('数据集电话', { exact: true }).fill('13100000002');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const row = page.getByRole('row').filter({ hasText: '13100000002' });
    await expect(row).toBeVisible();
    await row.locator('label.el-checkbox').click();
    await page.getByRole('button', { name: '创建任务', exact: true }).click();
    await expect(page).toHaveURL(/contact_ids=/);
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByRole('dialog').locator('button.el-dialog__headerbtn').click();
  });
  await check(
    'AI inspection, month reports, form assignment and share settings entrances',
    async () => {
      await go('/admin/tasks/inspection', 'AI-巡检');
      await expect(page.getByRole('tab', { name: '未匹配巡检', exact: true })).toBeVisible();
      await expect(page.getByRole('tab', { name: '评级巡检', exact: true })).toBeVisible();
      await go('/admin/dashBoard/seating', '账号统计');
      const grain = page.getByRole('combobox', { name: '报表粒度', exact: true });
      await grain.locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]').click();
      await page.getByRole('option', { name: '每月', exact: true }).click();
      await page.getByRole('button', { name: '生成报表', exact: true }).click();
      await expect(page).toHaveURL(/granularity=month/);
      await go('/admin/tasks/form', '表单管理');
      await page.getByRole('tab', { name: '表单分配', exact: true }).click();
      await expect(page.getByRole('combobox', { name: '分配表单', exact: true })).toBeVisible();
      await go('/admin/scene/share', '分享设置');
      await page.getByRole('button', { name: '新建', exact: true }).click();
      await expect(page.getByRole('combobox', { name: '分享接收账号', exact: true })).toBeVisible();
      await page.getByRole('dialog').locator('button.el-dialog__headerbtn').click();
    },
  );
}
