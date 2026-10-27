import { expect } from '@playwright/test';

export async function operationsChecks({ page, context, origin, check, go }) {
  const row = (text) => page.getByRole('row').filter({ hasText: text });
  const choose = async (label, option) => {
    await page
      .getByRole('combobox', { name: label, exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: option, exact: true }).click();
  };
  await check(
    'P0 calls compound search URL persistence evidence return and matching export',
    async () => {
      await go('/admin/operations/calls', '话单查询');
      await page.getByLabel('查询号码', { exact: true }).fill('13100000001');
      await page.getByRole('button', { name: '查询', exact: true }).click();
      await expect(row('13100000001')).toBeVisible();
      await expect(page).toHaveURL(/phone=13100000001/);
      const download = page.waitForEvent('download');
      await page.getByRole('button', { name: '导出当前结果', exact: true }).click();
      expect((await download).suggestedFilename()).toBe('filtered-calls.csv');
      await row('13100000001').getByRole('button', { name: '查看通话证据', exact: true }).click();
      await expect(page.getByRole('dialog')).toBeVisible();
      await page.getByRole('dialog').locator('button.el-drawer__close-btn').click();
      await expect(row('13100000001')).toBeVisible();
      await page.reload();
      await expect(page.getByLabel('查询号码', { exact: true })).toHaveValue('13100000001');
      await expect(row('13100000001')).toBeVisible();
      await page.getByLabel('话单视图名称', { exact: true }).fill('浏览器精确话单');
      await page.getByRole('button', { name: '保存视图', exact: true }).click();
      await page.getByRole('button', { name: '重置', exact: true }).click();
      await choose('话单常用视图', '浏览器精确话单');
      await expect(page.getByLabel('查询号码', { exact: true })).toHaveValue('13100000001');
      await page.getByLabel('查询号码', { exact: true }).fill('99999999999999');
      await page.getByRole('button', { name: '查询', exact: true }).click();
      await expect(page.getByText('共 0 条', { exact: true }).first()).toBeVisible();
      await page.getByRole('button', { name: '重置', exact: true }).click();
    },
  );
  await check('P2 form template publishing versions and contact tag dictionary', async () => {
    await go('/admin/operations/configs', '表单标签与连接');
    await page.getByRole('button', { name: '新增表单模板', exact: true }).click();
    await page.getByLabel('表单模板名称', { exact: true }).fill('浏览器补录表单');
    await page.getByRole('button', { name: '发布表单模板', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(row('浏览器补录表单')).toBeVisible();
    await row('浏览器补录表单').getByRole('button', { name: '基于此版编辑', exact: true }).click();
    await page.getByRole('button', { name: '发布表单模板', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(row('浏览器补录表单')).toHaveCount(2);
    await page.getByRole('tab', { name: '标签字典', exact: true }).click();
    await page.getByLabel('标签名称', { exact: true }).fill('浏览器重点');
    await page.getByRole('button', { name: '发布标签', exact: true }).click();
    await expect(row('浏览器重点')).toBeVisible();
    await page.getByLabel('标签名称', { exact: true }).fill('浏览器通话标签');
    await choose('标签对象', '通话');
    await page.getByRole('button', { name: '发布标签', exact: true }).click();
    await expect(row('浏览器通话标签')).toBeVisible();
    await page
      .getByRole('combobox', { name: '标签业务对象', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option').filter({ hasText: '13100000001' }).first().click();
    await choose('对象标签', '浏览器通话标签');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '保存对象标签', exact: true }).click();
    await expect(page.getByText('已保存', { exact: true }).last()).toBeVisible();
    await page.getByRole('tab', { name: '业务连接', exact: true }).click();
    await expect(page.getByLabel('回调超时', { exact: true })).toBeVisible();
    await page
      .getByRole('tabpanel')
      .filter({ visible: true })
      .getByRole('button', { name: '保存', exact: true })
      .click();
    await expect(page.getByText('操作成功', { exact: true }).last()).toBeVisible();
  });
  await check('P0 lead allocation follow-up manual form tags and history persist', async () => {
    await go('/admin/operations/leads', '线索与跟进');
    await page.getByText('公海线索', { exact: true }).click();
    await page.getByLabel('搜索线索', { exact: true }).fill('13100000002');
    await page.getByRole('button', { name: '搜索线索', exact: true }).click();
    await expect(row('Vue浏览器客户更新')).toBeVisible();
    await row('Vue浏览器客户更新').locator('label.el-checkbox').click();
    await choose('分配负责人', '浏览器admin');
    await page.getByRole('button', { name: '批量分配', exact: true }).click();
    await expect(row('Vue浏览器客户更新')).toHaveCount(0);
    await page.getByText('我的线索', { exact: true }).click();
    await expect(row('Vue浏览器客户更新')).toBeVisible();
    await row('Vue浏览器客户更新').getByRole('button', { name: '跟进详情', exact: true }).click();
    await page.getByLabel('跟进记录内容', { exact: true }).fill('已联系，明天跟进');
    await page.getByLabel('下次跟进时间', { exact: true }).fill('2027-01-01T10:00');
    await page.getByRole('button', { name: '保存跟进', exact: true }).click();
    await expect(page.getByLabel('跟进记录内容', { exact: true })).toHaveValue('');
    await page.getByRole('tab', { name: '人工补录', exact: true }).click();
    await page
      .getByRole('combobox', { name: '选择补录表单', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: '浏览器补录表单 v2', exact: true }).click();
    const panel = page.getByRole('tabpanel').filter({ visible: true });
    await panel.getByRole('textbox', { name: '跟进结果', exact: true }).fill('浏览器人工确认');
    await page.getByRole('button', { name: '保存补录', exact: true }).click();
    await page.getByRole('tab', { name: '客户标签', exact: true }).click();
    await choose('线索标签', '浏览器重点');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '保存标签', exact: true }).click();
    await page.getByRole('tab', { name: '操作历史', exact: true }).click();
    await expect(page.getByText('已联系，明天跟进', { exact: true })).toBeVisible();
    await page.getByRole('dialog').locator('button.el-drawer__close-btn').click();
    await page.reload();
    await expect(row('Vue浏览器客户更新')).toBeVisible();
  });
  await check('P0 teams membership leader and unified workplace save echo', async () => {
    await go('/admin/operations/teams', '团队与权限');
    await page.getByRole('button', { name: '新增坐席组', exact: true }).click();
    await page.getByLabel('坐席组名', { exact: true }).fill('浏览器运营组');
    await choose('坐席组成员', 'Vue新坐席');
    await page.keyboard.press('Escape');
    const skills = page.getByRole('combobox', { name: '接待技能', exact: true });
    await skills.fill('北京');
    await skills.press('Enter');
    await skills.fill('口腔');
    await skills.press('Enter');
    await page.keyboard.press('Escape');
    await page.getByLabel('组整理秒数', { exact: true }).fill('30');
    await page.getByRole('button', { name: '保存坐席组', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(row('浏览器运营组')).toContainText('Vue新坐席');
    await row('浏览器运营组').getByRole('button', { name: '编辑坐席组', exact: true }).click();
    await expect(page.getByLabel('组整理秒数', { exact: true })).toHaveValue('30');
    await expect(page.getByRole('dialog')).toContainText('北京');
    await expect(page.getByRole('dialog')).toContainText('口腔');
    await page.getByRole('dialog').locator('button.el-dialog__headerbtn').click();
    const policyLoad = page.waitForResponse(
      (r) => r.url().includes('/api/v1/ops/permissions/') && r.request().method() === 'GET',
    );
    await choose('权限用户', 'Vue新坐席');
    await policyLoad;
    await page.getByText('允许导出', { exact: true }).click();
    await expect(page.getByRole('checkbox', { name: '允许导出', exact: true })).toBeChecked();
    const policySave = page.waitForResponse(
      (r) => r.url().includes('/api/v1/ops/permissions/') && r.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: '保存操作权限', exact: true }).click();
    expect((await policySave).status()).toBe(200);
  });
  await check(
    'P1 reports configurable columns saved views drilldown and task operations',
    async () => {
      await go('/admin/operations/reports', '运营分析');
      await expect(page.getByRole('heading', { name: '运营报表', exact: true })).toBeVisible();
      await page.getByRole('button', { name: '生成报表', exact: true }).click();
      const reportDownload = page.waitForEvent('download');
      await page.getByRole('button', { name: '导出运营报表', exact: true }).click();
      expect((await reportDownload).suggestedFilename()).toBe('operations-report.csv');
      await expect(
        page.getByRole('button', { name: '下钻话单', exact: true }).first(),
      ).toBeVisible();
      await page.getByLabel('报表视图名称', { exact: true }).fill('浏览器日报');
      await page.getByRole('button', { name: '保存报表视图', exact: true }).click();
      await page.getByRole('button', { name: '下钻话单', exact: true }).first().click();
      await expect(page).toHaveURL(/operations\/calls/);
      await expect(page.getByRole('heading', { name: '话单查询', exact: true })).toBeVisible();
      await page.goBack();
      await expect(page.getByRole('heading', { name: '运营报表', exact: true })).toBeVisible();
      await page.getByRole('tab', { name: '任务运营台', exact: true }).click();
      await expect(row('Vue浏览器活动')).toBeVisible();
      await row('Vue浏览器活动').getByRole('button', { name: '运营详情', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('导入人数');
      await page.getByRole('dialog').locator('button.el-drawer__close-btn').click();
    },
  );
  await check('P2 notifications read navigate and personal configuration echo', async () => {
    await go('/admin/operations/notices', '通知中心');
    await expect(page.getByRole('button', { name: '查看事项', exact: true }).first()).toBeVisible();
    await page.getByRole('button', { name: '查看事项', exact: true }).first().click();
    await expect(page).toHaveURL(/operations\/(leads|calls)/);
    await go('/admin/operations/workspace', '坐席个人配置');
    await page.getByLabel('个人整理秒数', { exact: true }).fill('12');
    const saved = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/ops/workspace') && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: '保存个人配置', exact: true }).click();
    expect((await saved).status()).toBe(200);
    await page.reload();
    await expect(page.getByLabel('个人整理秒数', { exact: true })).toHaveValue('12');
  });
}
