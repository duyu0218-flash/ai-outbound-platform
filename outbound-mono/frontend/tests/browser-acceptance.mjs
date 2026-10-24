import { operationsChecks } from './operations-browser.mjs';
import { navigationRaceChecks } from './navigation-race.mjs';
import { chromium, expect } from '@playwright/test';
import { writeFile, mkdir } from 'node:fs/promises';
const origin = process.env.MONO_BROWSER_URL;
if (!origin) throw new Error('MONO_BROWSER_URL is required');
const browser = await chromium.launch({ headless: true }),
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }),
  page = await context.newPage(),
  errors = [],
  checks = [];
page.on('pageerror', (error) => errors.push(error.message));
page.on('response', (response) => {
  if (response.status() >= 400) console.log('HTTP', response.status(), response.url());
});
page.on('console', (message) => {
  if (message.type() === 'error') console.log('CONSOLE', message.text());
});
const check = async (name, work) => {
  await work();
  checks.push(name);
  console.log('PASS ' + name);
};
const select = (label) =>
  page
    .getByRole('combobox', { name: label, exact: true })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]');
const save = () =>
  page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
const row = (text) => page.getByRole('row').filter({ hasText: text });
async function login(role) {
  await page.goto(origin + '/' + role + '/login');
  await page.getByLabel('用户名', { exact: true }).fill('browser-' + role);
  await page.getByLabel('密码', { exact: true }).fill('SyntheticBrowserPass123!');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(origin + '/' + role);
  await expect(page.locator('.app-shell')).toBeVisible();
}
async function go(path, title) {
  await page.getByRole('menuitem').filter({ hasText: title }).first().click();
  await expect(page).toHaveURL(origin + path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0);
}
try {
  await navigationRaceChecks({
    browser,
    origin,
    accounts: Object.fromEntries(
      ['admin', 'agent'].map((role) => [
        role,
        { username: 'browser-' + role, password: 'SyntheticBrowserPass123!' },
      ]),
    ),
    check,
  });
  await check('admin login and dashboard', () => login('admin'));
  await check('contacts required validation create edit search persistence', async () => {
    await go('/admin/contacts', '客户管理');
    await page.getByRole('button', { name: '新增客户', exact: true }).click();
    await save();
    await expect(page.locator('.el-form-item__error')).not.toHaveCount(0);
    await page.getByLabel('手机号', { exact: true }).fill('13100000002');
    await page.getByLabel('姓名', { exact: true }).fill('Vue浏览器客户');
    await select('同意状态').click();
    await page.getByRole('option', { name: '已同意', exact: true }).click();
    await save();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(row('Vue浏览器客户')).toBeVisible();
    await row('Vue浏览器客户').getByRole('button', { name: '编辑', exact: true }).click();
    await page.getByLabel('姓名', { exact: true }).fill('Vue浏览器客户更新');
    await save();
    await expect(row('Vue浏览器客户更新')).toBeVisible();
    await page.getByLabel('搜索联系人').fill('13100000002');
    await page.getByRole('button', { name: '搜索', exact: true }).click();
    await expect(row('Vue浏览器客户更新')).toBeVisible();
    await page.reload();
    await expect(row('Vue浏览器客户更新')).toBeVisible();
  });
  await check('scripts create canvas drag save publish readonly', async () => {
    await go('/admin/scripts', '话术管理');
    await page.getByRole('button', { name: '新建话术', exact: true }).click();
    await page.getByLabel('名称', { exact: true }).fill('Vue浏览器话术');
    await page.getByLabel('话术内容', { exact: true }).fill('您好，这是经过批准的服务介绍。');
    await save();
    await expect(page.getByText('Vue浏览器话术', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '话术画布', exact: true }).click();
    await page.getByRole('button', { name: '创建画布', exact: true }).click();
    await expect(page.locator('.flow-node')).not.toHaveCount(0);
    const node = page.locator('.flow-node').first();
    const bounds = await node.boundingBox();
    await page.mouse.move(bounds.x + 30, bounds.y + 20);
    await page.mouse.down();
    await page.mouse.move(bounds.x + 80, bounds.y + 40);
    await page.mouse.up();
    await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
    await page.getByRole('dialog').getByRole('button', { name: '发布版本', exact: true }).click();
    await page.getByRole('button', { name: '确定', exact: true }).click();
    await expect(
      page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }),
    ).toBeDisabled();
    await page
      .getByRole('dialog')
      .locator('button.el-dialog__headerbtn,button.el-drawer__close-btn')
      .first()
      .click();
  });
  await check('campaign form remote contacts save and start', async () => {
    await go('/admin/campaigns', '外呼任务');
    await page.getByRole('button', { name: '创建任务', exact: true }).click();
    await page.getByLabel('名称', { exact: true }).fill('Vue浏览器活动');
    const field = page
      .getByRole('dialog')
      .locator('.el-form-item')
      .filter({ has: page.getByText('选择客户', { exact: true }) });
    await field.getByRole('combobox').click();
    await page.getByRole('option').filter({ hasText: '13100000002' }).click();
    await page.keyboard.press('Escape');
    await save();
    await expect(row('Vue浏览器活动')).toBeVisible();
    await row('Vue浏览器活动').getByRole('button', { name: '启动', exact: true }).click();
    await page.getByRole('button', { name: '确定', exact: true }).click();
    await expect(row('Vue浏览器活动')).toContainText('运行中');
  });
  await check('completed mock call transcript QA recording metrics events views', async () => {
    await go('/admin/calls', '通话记录');
    await row('13100000001').getByRole('button', { name: '事件', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    for (const tab of ['结构化转写', '结果与质检', '录音资产', '阶段指标', '事件']) {
      await page.getByRole('tab', { name: tab, exact: true }).click();
      await expect(page.getByRole('tabpanel').filter({ visible: true })).toBeVisible();
    }
    await page
      .getByRole('dialog')
      .locator('button.el-dialog__headerbtn,button.el-drawer__close-btn')
      .first()
      .click();
  });
  await check('terminal retry button submits real HTTP and advances next attempt', async () => {
    await go('/admin/calls', '通话记录');
    const target = row('13100000004');
    await expect(target).toContainText('1/2');
    await expect(target.getByRole('button', { name: '重拨', exact: true })).toBeEnabled();
    const request = page.waitForResponse(
      (response) => response.url().endsWith('/retry') && response.request().method() === 'POST',
    );
    await target.getByRole('button', { name: '重拨', exact: true }).click();
    await page.getByRole('button', { name: '确定', exact: true }).click();
    const response = await request;
    expect(response.status()).toBe(200);
    expect((await response.json()).status).toBe('dialing');
    await expect(target).toContainText('2/2');
    await expect(target).toContainText('拨号中');
    await expect(target.getByRole('button', { name: '重拨', exact: true })).toBeDisabled();
    await page.reload();
    await expect(row('13100000004')).toContainText('2/2');
  });
  await check('quality review saving and echo', async () => {
    await go('/admin/quality', '质检复核');
    const target = row('13100000001');
    await expect(target).toBeVisible();
    await target.getByRole('button', { name: '复核', exact: true }).click();
    await page.getByLabel('通话摘要', { exact: true }).fill('Vue人工复核已确认');
    await save();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });
  await check('reports group monitor billing actual completed call', async () => {
    await go('/admin/reports', '运营报表');
    await expect(page.getByRole('heading', { name: '通话报表', exact: true })).toBeVisible();
    await go('/admin/group-monitor', '分组监控');
    await expect(page.locator('.el-table')).toBeVisible();
    await go('/admin/billing', '账单与计费');
    await expect(page.getByText('费用为按秒折算的估算值，非运营商结算账单')).toBeVisible();
  });
  await check('contacts operations CSV import export batch DNC', async () => {
    await go('/admin/contacts-operations', '客户运营');
    await page.locator('input[type=file]').setInputFiles({
      name: 'browser-contacts.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from('phone,name,consent_state\n13100000003,Vue导入客户,consented\n'),
    });
    await expect(row('Vue导入客户')).toBeVisible();
    await row('Vue导入客户').locator('label.el-checkbox').click();
    await expect(row('Vue导入客户').getByRole('checkbox')).toBeChecked();
    await page.getByRole('button', { name: '批量禁打设置', exact: true }).click();
    await page.getByLabel('禁打原因', { exact: true }).fill('浏览器断言');
    await save();
    await expect(row('Vue导入客户')).toContainText('浏览器断言');
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: '导出', exact: true }).click();
    await expect((await download).suggestedFilename()).toMatch(/\.csv$/);
  });
  await check('users create edit password role fields', async () => {
    await go('/admin/users', '用户与座席');
    await page.getByRole('button', { name: '新增用户', exact: true }).click();
    await page.getByLabel('用户名', { exact: true }).fill('browser-created-agent');
    await page.getByLabel('姓名', { exact: true }).fill('Vue新坐席');
    await page.getByLabel('密码', { exact: true }).fill('SyntheticCreatedPass123!');
    await save();
    await expect(row('browser-created-agent')).toBeVisible();
  });
  await check('lines mock save echo and disable', async () => {
    await go('/admin/lines', '外呼线路');
    await page.getByRole('button', { name: '新增线路', exact: true }).click();
    await page.getByLabel('名称', { exact: true }).fill('Vue测试线路');
    await select('服务商').click();
    await page.getByRole('option').filter({ hasText: 'Mock' }).click();
    await save();
    await expect(row('Vue测试线路')).toBeVisible();
    await row('Vue测试线路').locator('.el-switch').click();
    await expect(row('Vue测试线路').getByRole('switch')).not.toBeChecked();
  });
  await check('knowledge create update persistence', async () => {
    await go('/admin/knowledge', '知识库');
    await page.getByRole('button', { name: '新建', exact: true }).click();
    await page.getByLabel('名称', { exact: true }).fill('Vue知识条目');
    await page.getByLabel('话术内容', { exact: true }).fill('该服务可以预约咨询。');
    await save();
    await expect(row('Vue知识条目')).toBeVisible();
  });
  await check('settings load save echo all five sections', async () => {
    await go('/admin/settings', '系统配置');
    for (const [tab, field] of [
      ['并发容量', '租户最大同时通话数'],
      ['AI 与语音', '模型名称'],
      ['短信配置', '短信签名'],
      ['合规策略', '时区'],
      ['接口与回调', '回调超时'],
    ]) {
      await page.getByRole('tab', { name: tab, exact: true }).click();
      await expect(page.getByLabel(field, { exact: true })).toBeVisible();
      await page
        .getByRole('tabpanel')
        .filter({ visible: true })
        .getByRole('button', { name: '保存', exact: true })
        .click();
      await expect(page.getByText('操作成功', { exact: true }).last()).toBeVisible();
    }
  });
  await operationsChecks({ page, context, origin, check, go });
  await check('P0-P1 human admission policy form saves and echoes', async () => {
    await go('/admin/product', '业务交付策略');
    await page.getByRole('tab', { name: '转人工与时间安排', exact: true }).click();
    await page.getByLabel('人工最大等待人数', { exact: true }).fill('7');
    await page.getByLabel('预计转人工比例', { exact: true }).fill('0.2');
    await page
      .getByRole('combobox', { name: '可接待团队', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: '浏览器运营组', exact: true }).click();
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: '发布策略', exact: true }).click();
    await expect(page.getByText('策略已发布，下次通话生效')).toBeVisible();
    await page.reload();
    await page.getByRole('tab', { name: '转人工与时间安排', exact: true }).click();
    await expect(page.getByLabel('人工最大等待人数', { exact: true })).toHaveValue('7');
    await expect(page.getByLabel('预计转人工比例', { exact: true })).toHaveValue('0.2');
    await expect(
      page.getByRole('tabpanel', { name: '转人工与时间安排', exact: true }),
    ).toContainText('浏览器运营组');
  });
  await check('product policy simulate publish and knowledge retrieval', async () => {
    await go('/admin/product', '业务交付策略');
    await page.getByRole('tab', { name: '试跑与版本', exact: true }).click();
    await page.getByRole('button', { name: '运行对话试跑', exact: true }).click();
    await expect(page.getByRole('cell', { name: '不用转人工', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '发布策略', exact: true }).click();
    await expect(page.getByText('策略已发布，下次通话生效')).toBeVisible();
    await page.getByRole('tab', { name: '知识导入与检索测试', exact: true }).click();
    await page.getByLabel('知识标题', { exact: true }).fill('Vue导入知识');
    await page.getByLabel('知识正文', { exact: true }).fill('支持周末预约咨询。');
    await page.getByRole('button', { name: '保存知识', exact: true }).click();
    await page.getByLabel('检索问题', { exact: true }).fill('周末预约');
    await page.getByRole('button', { name: '测试检索', exact: true }).click();
    await expect(page.getByRole('cell', { name: 'Vue导入知识', exact: true })).toBeVisible();
  });
  await check('system real service health audit and SMS tabs', async () => {
    await go('/admin/system', '监控与审计');
    await expect(page.getByText('服务健康状态', { exact: true })).toBeVisible();
    await expect(page.getByText('审计日志', { exact: true })).toBeVisible();
  });
  await check('browser back and logout session invalidation', async () => {
    await page.goBack();
    await expect(page).toHaveURL(origin + '/admin/product');
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/admin/login');
    expect(
      await page.evaluate(() => sessionStorage.getItem('ai-platform-access-token')),
    ).toBeNull();
  });
  if (process.env.MONO_CAPTURE_MANUAL === 'true') {
    const { captureManual } = await import('./manual-capture.mjs');
    await captureManual({ browser, origin });
  }
  await check('agent login workspace presence and admin boundary', async () => {
    await login('agent');
    await expect(page.getByText('浏览器软电话', { exact: true })).toBeVisible();
    await expect(page.getByText('服务器尚未启用WebRTC')).toBeVisible();
    await select('座席状态').click();
    await page.getByRole('option', { name: '离线', exact: true }).click();
    await expect(select('座席状态')).toContainText('离线');
    const agentToken = await page.evaluate(() =>
      sessionStorage.getItem('ai-platform-access-token'),
    );
    await expect
      .poll(async () => {
        const response = await context.request.get(origin + '/api/v1/auth/me', {
          headers: { Authorization: 'Bearer ' + agentToken },
        });
        return (await response.json()).agent_status;
      })
      .toBe('offline');
    await select('座席状态').click();
    await page.getByRole('option', { name: '空闲', exact: true }).click();
    await go('/agent/calls', '通话记录');
    await expect(page.getByRole('row').filter({ hasText: '13100000001' })).toHaveCount(0);
    await page.goto(origin + '/admin/contacts');
    await expect(page).toHaveURL(origin + '/agent');
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
  });
  await check('agent operations entrances use scoped data and read-only boundaries', async () => {
    await login('agent');
    for (const [path, label] of [
      ['calls', '话单查询'],
      ['leads', '线索与跟进'],
      ['reports', '运营报表'],
      ['notices', '通知中心'],
      ['workspace', '坐席个人配置'],
    ]) {
      await go('/agent/operations/' + path, label);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    }
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
  });
  await check('supervisor agent quality entrance uses the original supervisor role', async () => {
    await page.goto(origin + '/agent/login');
    await page.getByLabel('用户名', { exact: true }).fill('browser-supervisor');
    await page.getByLabel('密码', { exact: true }).fill('SyntheticBrowserPass123!');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/agent');
    await go('/agent/quality', '质检复核');
    await expect(page.getByRole('heading', { name: '质检复核', exact: true })).toBeVisible();
    await expect(page.getByText('待复核', { exact: true }).first()).toBeVisible();
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/agent/login');
  });
  await check('wrong credentials error and unauthorized protected entrance', async () => {
    await page.goto(origin + '/admin/login');
    await page.getByLabel('用户名', { exact: true }).fill('browser-admin');
    await page.getByLabel('密码', { exact: true }).fill('wrong-synthetic-password');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.locator('.el-message--error')).toBeVisible();
    await expect(page).toHaveURL(origin + '/admin/login');
    await page.goto(origin + '/admin/contacts');
    await expect(page).toHaveURL(origin + '/admin/login');
  });
  expect(errors).toEqual([]);
  await mkdir('target', { recursive: true });
  await writeFile(
    'target/frontend-browser-result.json',
    JSON.stringify(
      {
        passed: true,
        checks,
        errors,
        environment: 'Chromium local Testcontainers; no real SIP device',
      },
      null,
      2,
    ),
  );
} catch (error) {
  await mkdir('target', { recursive: true });
  await page.screenshot({ path: 'target/frontend-browser-failure.png', fullPage: true });
  await writeFile('target/frontend-browser-failure.html', await page.content());
  console.error('FAILED after ' + checks.length + ' checks', error);
  console.error('pageerrors', errors);
  throw error;
} finally {
  await context.close();
  await browser.close();
}
