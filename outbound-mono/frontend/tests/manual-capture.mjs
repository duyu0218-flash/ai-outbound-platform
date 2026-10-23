import { expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

// Only run against FrontendBrowserIT's disposable MySQL and synthetic accounts.
export async function captureManual({ browser, origin }) {
  const output = resolve('../docs/user-guide');
  await mkdir(output + '/images', { recursive: true });
  const context = await browser.newContext({
    viewport: { width: 1600, height: 1100 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    reducedMotion: 'reduce',
  });
  const page = await context.newPage();
  const shots = [];
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  async function shot(name) {
    // Vue lazy tabs and drawers mount after the click handler returns.
    await page.waitForTimeout(500);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({
      path: output + '/images/' + name + '.png',
      fullPage: true,
      animations: 'disabled',
    });
    shots.push({ image: name + '.png', path: new URL(page.url()).pathname });
    console.log('MANUAL ' + name);
  }
  async function login(role) {
    await page.goto(origin + '/' + role + '/login');
    await expect(page.getByLabel('用户名', { exact: true })).toBeVisible();
    await shot(role + '-login');
    await page.getByLabel('用户名', { exact: true }).fill('browser-' + role);
    await page.getByLabel('密码', { exact: true }).fill('SyntheticBrowserPass123!');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/' + role);
  }
  async function go(role, path, label, image) {
    await page.getByRole('menuitem').filter({ hasText: label }).first().click();
    await expect(page).toHaveURL(origin + '/' + role + (path ? '/' + path : ''));
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await page.waitForLoadState('networkidle');
    await shot(image);
  }
  async function close() {
    await page
      .getByRole('dialog')
      .locator('button.el-dialog__headerbtn,button.el-drawer__close-btn')
      .first()
      .click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
  }
  async function tab(label, image) {
    await page.getByRole('tab', { name: label, exact: true }).click();
    await page.waitForLoadState('networkidle');
    await shot(image);
  }
  try {
    await login('admin');
    await shot('admin-dashboard');
    await go('admin', 'contacts', '客户管理', 'contacts');
    await page.getByRole('button', { name: '新增客户', exact: true }).click();
    await page.getByLabel('姓名', { exact: true }).fill('操作手册演示客户');
    await shot('contact-form');
    await close();
    await go('admin', 'contacts-operations', '客户运营', 'contact-import');
    await go('admin', 'scripts', '话术管理', 'scripts');
    await page.getByRole('button', { name: '话术画布', exact: true }).first().click();
    await expect(page.locator('.flow-node').first()).toBeVisible();
    await shot('script-flow');
    await close();
    await go('admin', 'knowledge', '知识库', 'knowledge');
    await go('admin', 'campaigns', '外呼任务', 'campaigns');
    await page.getByRole('button', { name: '创建任务', exact: true }).click();
    await page.getByLabel('名称', { exact: true }).fill('操作手册演示任务');
    await shot('campaign-form');
    await close();
    await go('admin', 'calls', '通话记录', 'calls');
    await page
      .getByRole('row')
      .filter({ hasText: '13100000001' })
      .getByRole('button', { name: '事件', exact: true })
      .click();
    for (const [label, name] of [
      ['结构化转写', 'call-transcript'],
      ['结果与质检', 'call-result'],
      ['录音资产', 'call-recording'],
      ['阶段指标', 'call-metrics'],
      ['事件', 'call-events'],
    ])
      await tab(label, name);
    await close();
    await go('admin', 'operations/calls', '话单查询', 'operations-calls');
    await go('admin', 'operations/leads', '线索与跟进', 'leads');
    await page
      .getByRole('row')
      .filter({ hasText: 'Vue浏览器客户更新' })
      .getByRole('button', { name: '跟进详情', exact: true })
      .click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByLabel('跟进记录内容', { exact: true })).toBeVisible();
    await shot('lead-follow');
    await tab('人工补录', 'lead-form');
    await tab('客户标签', 'lead-tags');
    await tab('操作历史', 'lead-history');
    await close();
    await go('admin', 'quality', '质检复核', 'quality');
    await go('admin', 'operations/reports', '运营分析', 'operations-reports');
    await tab('任务运营台', 'task-operations');
    await page.getByRole('button', { name: '运营详情', exact: true }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('dialog')).toContainText('导入人数');
    await shot('task-detail');
    await close();
    await go('admin', 'reports', '运营报表', 'reports');
    await go('admin', 'group-monitor', '分组监控', 'groups');
    await go('admin', 'billing', '账单与计费', 'billing');
    await go('admin', 'users', '用户与座席', 'users');
    await page.getByRole('button', { name: '新增用户', exact: true }).click();
    await shot('user-form');
    await close();
    await go('admin', 'operations/teams', '团队与权限', 'teams');
    await page.getByRole('button', { name: '编辑坐席组', exact: true }).first().click();
    await shot('team-form');
    await close();
    await go('admin', 'lines', '外呼线路', 'lines');
    await page.getByRole('button', { name: '新增线路', exact: true }).click();
    await shot('line-form');
    await close();
    await go('admin', 'settings', '系统配置', 'settings-capacity');
    for (const [label, name, field] of [
      ['AI 与语音', 'settings-ai', '模型名称'],
      ['短信配置', 'settings-sms', '短信签名'],
      ['合规策略', 'settings-compliance', '时区'],
      ['接口与回调', 'settings-integration', '回调超时'],
    ]) {
      await page.getByRole('tab', { name: label, exact: true }).click();
      await expect(page.getByLabel(field, { exact: true })).toBeVisible();
      await shot(name);
    }
    await go('admin', 'product', '业务交付策略', 'product');
    for (const [label, name] of [
      ['信息采集', 'product-slots'],
      ['转人工与时间安排', 'product-handoff'],
      ['试跑与版本', 'product-test'],
      ['预约回拨', 'product-callback'],
      ['人工跟进', 'product-work'],
      ['交付统计', 'product-statistics'],
      ['交付结果', 'product-results'],
      ['知识导入与检索测试', 'product-knowledge'],
    ])
      await tab(label, name);
    await go('admin', 'operations/configs', '表单标签与连接', 'config-forms');
    await tab('标签字典', 'config-tags');
    await tab('业务连接', 'config-connections');
    await go('admin', 'operations/notices', '通知中心', 'notices');
    await go('admin', 'operations/workspace', '坐席个人配置', 'admin-workspace');
    await go('admin', 'system', '监控与审计', 'system');
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/admin/login');
    await login('agent');
    await expect(page.getByText('服务器尚未启用WebRTC')).toBeVisible();
    await shot('agent-workbench');
    for (const [path, label, name] of [
      ['calls', '通话记录', 'agent-calls'],
      ['operations/calls', '话单查询', 'agent-operations-calls'],
      ['operations/leads', '线索与跟进', 'agent-leads'],
      ['operations/reports', '运营报表', 'agent-reports'],
      ['operations/notices', '通知中心', 'agent-notices'],
      ['operations/workspace', '坐席个人配置', 'agent-settings'],
    ])
      await go('agent', path, label, name);
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/agent/login');
    expect(errors).toEqual([]);
    await writeFile(
      output + '/capture-manifest.json',
      JSON.stringify(
        {
          source:
            'outbound-mono; FrontendBrowserIT disposable MySQL; mock telephony; synthetic data',
          capturedOn: '2026-10-23',
          viewport: { width: 1600, height: 1100 },
          screenshots: shots,
          pageErrors: errors,
        },
        null,
        2,
      ) + '\n',
    );
  } finally {
    await context.close();
  }
}
