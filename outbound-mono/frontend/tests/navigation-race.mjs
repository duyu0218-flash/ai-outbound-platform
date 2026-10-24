import { chromium, expect } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { pathToFileURL } from 'node:url';

export async function navigationRaceChecks({ browser, origin, accounts, check }) {
  const runtimeErrors = [];
  async function session(role, work) {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    page.on('pageerror', (error) => runtimeErrors.push(error.message));
    try {
      await page.goto(origin + '/' + role + '/login');
      await page.getByLabel('用户名', { exact: true }).fill(accounts[role].username);
      await page.getByLabel('密码', { exact: true }).fill(accounts[role].password);
      await page.getByRole('button', { name: '登录', exact: true }).click();
      await expect(page).toHaveURL(origin + '/' + role);
      // The agent workbench has an event stream; use a finite page for test setup.
      await page.goto(origin + '/' + role + '/operations/notices');
      await expect(page.getByRole('heading', { name: '通知中心', exact: true })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await work(page);
    } finally {
      await context.close();
    }
  }

  for (const role of ['admin', 'agent']) {
    for (const [endpoint, pending] of [
      ['me', false],
      ['views', false],
      ['views', true],
    ]) {
      await check(
        `${role} delayed report ${endpoint} cannot change ${pending ? 'a pending' : 'the next'} page`,
        () =>
          session(role, async (page) => {
            let release;
            let completed;
            let held = false;
            const gate = new Promise((resolve) => (release = resolve));
            const delivered = new Promise((resolve) => (completed = resolve));
            const pattern = '**/api/v1/ops/' + endpoint;
            await page.route(pattern, async (route) => {
              if (held) return route.continue();
              const response = await route.fetch();
              held = true;
              await gate;
              try {
                await route.fulfill({ response });
              } finally {
                completed();
              }
            });
            try {
              const reportLabel = role === 'admin' ? '运营分析' : '运营报表';
              await page.getByRole('menuitem').filter({ hasText: reportLabel }).first().click();
              await expect(
                page.getByRole('heading', { name: '运营报表', exact: true }),
              ).toBeVisible();
              await expect.poll(() => held).toBe(true);
              const destinations =
                role === 'admin'
                  ? [
                      ['团队与权限', 'teams'],
                      ['表单标签与连接', 'configs'],
                    ]
                  : [
                      ['话单查询', 'calls'],
                      ['通知中心', 'notices'],
                    ];
              if (pending) {
                await page.evaluate(
                  (path) => {
                    const router =
                      document.querySelector('#app').__vue_app__.config.globalProperties.$router;
                    const gate = { started: false };
                    const blocked = new Promise((resolve) => (gate.release = resolve));
                    gate.remove = router.beforeEach((to) => {
                      if (to.path !== path) return;
                      gate.started = true;
                      return blocked;
                    });
                    window.__navigationTestGate = gate;
                  },
                  '/' + role + '/operations/' + destinations[0][1],
                );
              }
              for (const [index, [label, path]] of destinations.entries()) {
                await page.getByRole('menuitem').filter({ hasText: label }).first().click();
                if (pending && index === 0) {
                  await expect
                    .poll(() => page.evaluate(() => window.__navigationTestGate.started))
                    .toBe(true);
                  release();
                  await delivered;
                  await page.waitForTimeout(750);
                  await page.evaluate(() => {
                    window.__navigationTestGate.remove();
                    window.__navigationTestGate.release();
                  });
                }
                await expect(page).toHaveURL(origin + '/' + role + '/operations/' + path);
              }
              // Release a real server response only after both menu clicks completed.
              release();
              await delivered;
              // Observe late continuations after release; do not insert waits between menu clicks.
              await page.waitForTimeout(750);
              await page.waitForLoadState('networkidle');
              const last = destinations.at(-1)[1];
              await expect(page).toHaveURL(origin + '/' + role + '/operations/' + last);
              await expect(
                page.getByRole('heading', {
                  name: role === 'admin' ? '表单标签与业务连接' : '通知中心',
                  exact: true,
                }),
              ).toBeVisible();
              await expect(page.locator('.el-alert--error:visible')).toHaveCount(0);
            } finally {
              release();
              await page.unrouteAll({ behavior: 'wait' });
            }
          }),
      );
    }
    await check(`${role} current-page filters still apply reset and survive reload`, () =>
      session(role, async (page) => {
        await page.getByRole('menuitem').filter({ hasText: '话单查询' }).first().click();
        await expect(page.getByLabel('查询号码')).toBeVisible();
        await page.getByLabel('查询号码').fill('13100000099');
        await page.getByRole('button', { name: '查询', exact: true }).click();
        await expect(page).toHaveURL((url) => url.searchParams.get('phone') === '13100000099');
        await page.reload();
        await expect(page.getByLabel('查询号码')).toHaveValue('13100000099');
        await page.getByRole('button', { name: '重置', exact: true }).click();
        await expect(page).toHaveURL(origin + '/' + role + '/operations/calls');
        await expect(page.getByLabel('查询号码')).toHaveValue('');
        await page.evaluate(
          (path) => {
            const router =
              document.querySelector('#app').__vue_app__.config.globalProperties.$router;
            window.__cancelledNavigationCount = 0;
            window.__removeNavigationTestGuard = router.beforeEach((to) => {
              if (to.path === path) {
                window.__cancelledNavigationCount++;
                return false;
              }
            });
          },
          '/' + role + '/operations/notices',
        );
        await page.getByRole('menuitem').filter({ hasText: '通知中心' }).first().click();
        await expect.poll(() => page.evaluate(() => window.__cancelledNavigationCount)).toBe(1);
        await page.getByLabel('查询号码').fill('13100000099');
        await page.getByRole('button', { name: '查询', exact: true }).click();
        await expect(page).toHaveURL((url) => url.searchParams.get('phone') === '13100000099');
        await page.evaluate(() => window.__removeNavigationTestGuard());
        await page
          .getByRole('menuitem')
          .filter({ hasText: role === 'admin' ? '运营分析' : '运营报表' })
          .first()
          .click();
        await page.waitForLoadState('networkidle');
        await page
          .getByRole('combobox', { name: '报表维度' })
          .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
          .click();
        await page.getByRole('option', { name: '坐席', exact: true }).click();
        await page.getByRole('button', { name: '生成报表', exact: true }).click();
        await expect(page).toHaveURL((url) => url.searchParams.get('dimension') === 'agent');
        await page.reload();
        await expect(
          page
            .getByRole('combobox', { name: '报表维度' })
            .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]'),
        ).toContainText('坐席');
      }),
    );
  }
  expect(runtimeErrors).toEqual([]);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const origin = process.env.MONO_BROWSER_URL;
  if (!origin) throw new Error('MONO_BROWSER_URL is required');
  const accounts = process.env.MONO_TEST_ACCOUNTS
    ? JSON.parse(await readFile(process.env.MONO_TEST_ACCOUNTS, 'utf8'))
    : Object.fromEntries(
        ['admin', 'agent'].map((role) => [
          role,
          { username: 'browser-' + role, password: 'SyntheticBrowserPass123!' },
        ]),
      );
  const checks = [];
  const browser = await chromium.launch({ headless: true });
  const file = process.env.MONO_NAVIGATION_RESULT || 'target/navigation-race-result.json';
  try {
    await navigationRaceChecks({
      browser,
      origin,
      accounts,
      check: async (name, work) => {
        try {
          await work();
          checks.push({ name, passed: true });
          console.log('PASS ' + name);
        } catch (error) {
          checks.push({ name, passed: false, error: error.message });
          throw error;
        }
      },
    });
  } finally {
    await mkdir(dirname(file), { recursive: true });
    await writeFile(file, JSON.stringify({ origin, checks }, null, 2));
    await browser.close();
  }
}
