import { expect } from '@playwright/test';
export async function competitiveChecks({ page, check, go }) {
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
