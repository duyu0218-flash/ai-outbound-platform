import { chromium, expect } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
const origin = process.env.MONO_BROWSER_URL,
  checks = [],
  errors = [];
const browser = await chromium.launch({
  headless: true,
  args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
});
const context = await browser.newContext({
  permissions: ['microphone'],
  viewport: { width: 1440, height: 1000 },
});
const page = await context.newPage();
page.on('pageerror', (e) => errors.push(e.message));
async function check(name, fn) {
  await fn();
  checks.push(name);
  console.log('PASS ' + name);
}
try {
  await check('login and actual navigation', async () => {
    await page.goto(origin + '/admin/login');
    await page.getByLabel('用户名', { exact: true }).fill(process.env.BAILIAN_TEST_USER);
    await page.getByLabel('密码', { exact: true }).fill('SyntheticTrialPass123!');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('menuitem').filter({ hasText: '百炼业务试聊' }).click();
    await expect(page.getByRole('heading', { name: '百炼业务试聊' })).toBeVisible();
  });
  await check('configuration encrypted save and reload', async () => {
    await page.getByText('百炼账号与应用配置', { exact: true }).click();
    await page.getByLabel('已发布应用版本标记', { exact: true }).fill('browser-v1');
    await page.getByRole('button', { name: '加密保存百炼配置' }).click();
    await expect(page.getByText('配置已加密保存', { exact: true })).toBeVisible();
    await page.reload();
    await page.getByText('百炼账号与应用配置', { exact: true }).click();
    await expect(page.getByLabel('已发布应用版本标记', { exact: true })).toHaveValue('browser-v1');
    await expect(page.getByLabel('百炼 API Key', { exact: true })).toHaveValue('');
  });
  await check('create browser-only trial', async () => {
    await page.getByRole('combobox', { name: '测试客户', exact: true }).click();
    await page
      .getByRole('option', {
        name: '百炼测试客户 #' + process.env.BAILIAN_TEST_CONTACT,
        exact: true,
      })
      .click();
    await page
      .getByLabel('本次业务话术', { exact: true })
      .fill('先说明内部测试，再询问服务意向，保存草稿后等待人工确认。');
    await page.getByText('我已选择测试客户，并授权本次付费供应商试聊', { exact: true }).click();
    await page.getByRole('button', { name: '创建并连接试聊' }).click();
    await expect(page.getByRole('button', { name: '开始说话', exact: true })).toBeEnabled({
      timeout: 15000,
    });
  });
  await check('microphone PCM and reviewed output', async () => {
    await page.getByRole('button', { name: '开始说话', exact: true }).click();
    await expect(page.getByRole('button', { name: '结束说话', exact: true })).toBeEnabled();
    await page.waitForTimeout(350);
    await page.getByRole('button', { name: '结束说话', exact: true }).click();
    await expect(page.locator('.dialogue')).toContainText('我想了解服务');
    await expect(page.locator('.dialogue')).toContainText('助手（已审核）', { timeout: 15000 });
  });
  await check('multi-turn text and interruption', async () => {
    await expect(page.getByRole('button', { name: '发送文字', exact: true })).toBeEnabled();
    await page.getByLabel('文字试聊', { exact: true }).fill('我希望进一步了解服务');
    await page.getByRole('button', { name: '发送文字', exact: true }).click();
    await expect(page.locator('.dialogue')).toContainText('我希望进一步了解服务');
    await expect(page.locator('.dialogue li').filter({ hasText: '助手（已审核）' })).toHaveCount(
      2,
      { timeout: 15000 },
    );
    await page.getByRole('button', { name: '打断回复', exact: true }).click();
    await expect(page.getByRole('button', { name: '开始说话', exact: true })).toBeEnabled();
  });
  await check('end and persist after reload', async () => {
    await page.getByRole('button', { name: '结束试聊', exact: true }).click();
    await expect(page.getByRole('button', { name: '刷新记录', exact: true })).toBeEnabled();
    await page.reload();
    await page.getByRole('button', { name: '查看', exact: true }).first().click();
    await expect(page.locator('.dialogue')).toContainText('我希望进一步了解服务');
  });
  await check('confirmed result writes existing lead workflow', async () => {
    await page
      .getByRole('combobox', { name: '意向', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: '有意向', exact: true }).click();
    await page.getByLabel('跟进记录', { exact: true }).fill('浏览器试聊验收：愿意进一步了解。');
    await page.getByText('我已核对转写、意向和时间，确认写入所选测试客户', { exact: true }).click();
    await page.getByRole('button', { name: '确认写入线索跟进' }).click();
    await expect(page.getByRole('button', { name: '查看客户跟进' })).toBeVisible();
    await page.getByRole('button', { name: '查看客户跟进' }).click();
    await expect(page).toHaveURL(new RegExp('/admin/operations/leads'));
    await expect(page.locator('body')).toContainText('百炼测试客户');
  });
  await check('return route and source entry', async () => {
    await page.goto(origin + '/admin/bailian-trial');
    await page.getByRole('button', { name: '返回语音方案对比' }).click();
    await expect(page).toHaveURL(origin + '/admin/voice-ai');
    await page.getByRole('button', { name: '百炼多模态套件业务试聊' }).click();
    await expect(page).toHaveURL(origin + '/admin/bailian-trial');
  });
  await page.screenshot({ path: 'target/bailian-browser.png', fullPage: true });
  expect(errors).toEqual([]);
} finally {
  await writeFile(
    'target/bailian-browser-results.json',
    JSON.stringify({ checks, errors }, null, 2),
  );
  await browser.close();
}
