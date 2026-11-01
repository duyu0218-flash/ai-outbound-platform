import { chromium, expect } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import { marketingChecks } from './marketing-browser.mjs';

const origin = process.env.MONO_BROWSER_URL;
if (!origin || !process.env.MONO_BROWSER_ADMIN_TOKEN || !process.env.MONO_BROWSER_AGENT_TOKEN)
  throw new Error('Local marketing test URL and synthetic credentials are required');
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
const page = await context.newPage(),
  errors = [],
  checks = [];
page.on('pageerror', (error) => errors.push(error.message));
const check = async (name, work) => {
  await work();
  checks.push(name);
  console.log('PASS ' + name);
};
const go = async (path, title) => {
  await page.getByRole('menuitem').filter({ hasText: title }).first().click();
  await expect(page).toHaveURL(origin + path);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0);
};
try {
  await page.goto(origin + '/admin/login');
  await page.evaluate(
    (token) => sessionStorage.setItem('ai-platform-access-token', token),
    process.env.MONO_BROWSER_ADMIN_TOKEN,
  );
  await page.goto(origin + '/admin');
  await marketingChecks({ page, origin, check, go });
  await check('marketing logout unauthenticated entrance and agent boundary', async () => {
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await expect(page).toHaveURL(origin + '/admin/login');
    expect(
      await page.evaluate(() => sessionStorage.getItem('ai-platform-access-token')),
    ).toBeNull();
    await page.goto(origin + '/admin/marketing/journeys');
    await expect(page).toHaveURL(origin + '/admin/login');
    await page.evaluate(
      (token) => sessionStorage.setItem('ai-platform-access-token', token),
      process.env.MONO_BROWSER_AGENT_TOKEN,
    );
    await page.goto(origin + '/admin/marketing/datasets');
    await expect(page).toHaveURL(origin + '/agent');
    await expect(page.getByRole('menuitem').filter({ hasText: '数据包与撞库' })).toHaveCount(0);
  });
  expect(errors).toEqual([]);
  await writeFile(
    'target/marketing-browser-result.json',
    JSON.stringify(
      {
        passed: true,
        checks,
        errors,
        environment:
          'Local Chromium and Testcontainers MySQL; synthetic credentials; no paid providers',
      },
      null,
      2,
    ),
  );
} catch (error) {
  await mkdir('target', { recursive: true });
  await page.screenshot({ path: 'target/marketing-browser-failure.png', fullPage: true });
  await writeFile('target/marketing-browser-failure.html', await page.content());
  console.error('FAILED after ' + checks.length + ' checks', error);
  console.error('pageerrors', errors);
  throw error;
} finally {
  await context.close();
  await browser.close();
}
