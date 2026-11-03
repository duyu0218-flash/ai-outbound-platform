import { expect } from '@playwright/test';
export async function workspaceChecks({ page, origin, check, go }) {
  await check('four navigation groups and all visible module entrances render', async () => {
    await expect(page.locator('.sidebar .nav-group')).toHaveText([
      'AI 外呼',
      '短信营销',
      '营销中台',
      '系统',
    ]);
    await page.getByRole('button', { name: '展开或收起AI 外呼', exact: true }).click();
    await expect(page.getByRole('menuitem').filter({ hasText: '话术管理' })).toBeHidden();
    await page.getByRole('button', { name: '展开或收起AI 外呼', exact: true }).click();
    await page.getByRole('button', { name: '帮助', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: '创建一次外呼任务', exact: true }),
    ).toBeVisible();
    await page.locator('.el-drawer__close-btn').click();
    const labels = await page.getByRole('menuitem').allTextContents();
    for (const label of labels) {
      await page.getByRole('menuitem').filter({ hasText: label.trim() }).first().click();
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0);
    }
    await go('/admin/reports', '外呼报表');
    for (const label of ['运营分析', '分组统计', '运营报表']) {
      await page.getByRole('tab', { name: label, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    }
  });
  await check('script copy keeps flow draft and owner change echoes', async () => {
    await go('/admin/scripts', '话术管理');
    const source = page.getByRole('row').filter({ hasText: 'Vue浏览器话术' }).first();
    await source.getByRole('button', { name: '复制', exact: true }).click();
    await expect(page.getByLabel('业务目标', { exact: true })).toHaveValue('了解咨询意向');
    await expect(page.getByText('发布 v0', { exact: false })).toBeVisible();
    await page
      .getByRole('combobox', { name: '负责人', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: '浏览器agent', exact: true }).click();
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await page.getByRole('tab', { name: '流程画布', exact: true }).click();
    await page.getByRole('button', { name: '打开话术画布', exact: true }).click();
    await expect(page.locator('.flow-node')).not.toHaveCount(0);
    await page.getByRole('dialog').locator('.el-dialog__headerbtn').click();
    await page.getByRole('button', { name: '返回话术列表', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: 'Vue浏览器话术（副本）' })).toContainText(
      '浏览器agent',
    );
  });
  await check(
    'local script probe uses current edits without saving or changing publication',
    async () => {
      await go('/admin/scripts', '话术管理');
      const source = page
        .getByRole('row')
        .filter({ has: page.getByRole('cell', { name: 'Vue浏览器话术', exact: true }) });
      await source.getByRole('button', { name: '配置话术', exact: true }).click();
      await expect(page.locator('.el-main .actions .el-tag')).toHaveText('已发布');
      await page.getByRole('tab', { name: '知识与动作', exact: true }).click();
      await page
        .getByRole('combobox', { name: '关联知识库', exact: true })
        .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
        .click();
      await page.getByRole('option', { name: 'Vue知识库 · 发布v1', exact: true }).click();
      await page.keyboard.press('Escape');
      await page.getByRole('tab', { name: '调试', exact: true }).click();
      await page.getByLabel('调试输入', { exact: true }).fill('预约咨询');
      const writes = [];
      const capture = (request) => {
        if (request.method() === 'PUT' && request.url().endsWith('/draft'))
          writes.push(request.url());
      };
      page.on('request', capture);
      try {
        await page.getByRole('button', { name: '本地预检', exact: true }).click();
        await expect(page.locator('.knowledge-hit')).toContainText('预约咨询');
        await expect(page.getByRole('region', { name: '话术预检结果' })).toContainText(
          '未配置采集字段',
        );
        await expect(page.locator('.el-main .actions .el-tag')).toHaveText('已发布');
        expect(writes).toEqual([]);
      } finally {
        page.off('request', capture);
      }
      await page.reload();
      await expect(source).toContainText('已发布');
      await source.getByRole('button', { name: '配置话术', exact: true }).click();
      await page.getByRole('tab', { name: '知识与动作', exact: true }).click();
      await expect(
        page.locator('.el-select__selected-item').filter({ hasText: 'Vue知识库' }),
      ).toHaveCount(0);
      await page.getByRole('button', { name: '返回话术列表', exact: true }).click();
    },
  );
  await check('SMS signature template list approval version copy and reload', async () => {
    await go('/admin/sms/content', '签名与模板');
    await page.getByRole('button', { name: '新建模板', exact: true }).click();
    await page.getByLabel('模板名称', { exact: true }).fill('独立短信模板验收');
    await page.getByLabel('模板正文', { exact: true }).fill('您好，{{name}}，请确认预约时间。');
    await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
    const target = page.getByRole('row').filter({ hasText: '独立短信模板验收' });
    await target.getByRole('button', { name: '内部批准', exact: true }).click();
    await expect(target).toContainText('内部已批准');
    await page.reload();
    await expect(target).toContainText('内部已批准');
    await target.getByRole('button', { name: '复制为新版本', exact: true }).click();
    await expect(page.getByLabel('模板正文', { exact: true })).toHaveValue(
      '您好，{{name}}，请确认预约时间。',
    );
    await page.getByRole('dialog').locator('.el-dialog__headerbtn').click();
    await page.getByRole('tab', { name: '短信签名', exact: true }).click();
    await page.getByRole('button', { name: '新建签名', exact: true }).click();
    await page.getByLabel('签名名称', { exact: true }).fill('内部验收签名');
    await page.getByLabel('品牌 / 企业主体', { exact: true }).fill('验收企业');
    await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('row').filter({ hasText: '内部验收签名' })).toBeVisible();
  });
  await check(
    'Excel template download CSV preview async import batch reuse and errors download',
    async () => {
      await go('/admin/contacts', '客户名单');
      await page.getByRole('button', { name: '导入号码', exact: true }).click();
      const downloading = page.waitForEvent('download');
      await page
        .getByRole('dialog')
        .getByRole('button', { name: '下载导入模板', exact: true })
        .click();
      expect((await downloading).suggestedFilename()).toBe('外呼名单导入模板.xlsx');
      await page.getByLabel('上传号码文件', { exact: true }).setInputFiles({
        name: '浏览器导入批次.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(
          '手机号,姓名,同意状态,禁止呼叫\n13100000050,批次客户,已同意,否\n13100000050,重复,已同意,否\nbad,错误,未知,否\n',
        ),
      });
      await expect(page.getByText('导入预览（尚未保存）', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: '确认导入', exact: true }).click();
      await expect(page.getByText('导入结果', { exact: true })).toBeVisible({ timeout: 30000 });
      const failed = page.waitForEvent('download');
      await page.getByRole('button', { name: '下载错误明细', exact: true }).click();
      expect((await failed).suggestedFilename()).toBe('导入错误明细.csv');
      await page.getByRole('button', { name: '完成', exact: true }).click();
      await page.getByRole('tab', { name: '导入批次', exact: true }).click();
      await expect(
        page.getByRole('row').filter({ hasText: '浏览器导入批次.csv' }).last(),
      ).toContainText('失败 1');
      await page
        .getByRole('row')
        .filter({ hasText: '浏览器导入批次.csv' })
        .last()
        .getByRole('button', { name: '创建外呼任务', exact: true })
        .click();
      await expect(page.getByRole('dialog')).toBeVisible();
      await page.getByLabel('任务名称').fill('批次复用验收');
      await page
        .getByRole('combobox', { name: '已发布话术', exact: true })
        .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
        .click();
      await page.getByRole('option', { name: 'Vue浏览器话术 · v1', exact: true }).click();
      await page.getByRole('button', { name: '下一步', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('预选 1 人');
      await page.getByRole('dialog').locator('.el-dialog__headerbtn').click();
    },
  );

  const select = (label) =>
    page
      .getByRole('combobox', { name: label, exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]');
  await check(
    'knowledge directories persist empty paths and document segments are reviewed before draft creation',
    async () => {
      await go('/admin/knowledge', '知识库');
      await page.getByRole('button', { name: '新建知识库', exact: true }).click();
      await page.getByLabel('知识库名称', { exact: true }).fill('完整方案知识库');
      await page.getByRole('button', { name: '创建知识库', exact: true }).click();
      await page.getByRole('button', { name: '新建分类', exact: true }).click();
      await page.getByLabel('目录路径').fill('服务/办理材料');
      await page.getByRole('button', { name: '保存分类', exact: true }).click();
      await page.reload();
      await expect(page.getByText('服务/办理材料', { exact: false }).first()).toBeVisible();
      await page.getByRole('tab', { name: '文档解析与分段', exact: true }).click();
      await page.getByLabel('上传知识文档', { exact: true }).setInputFiles({
        name: '办理说明.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from('办理材料要求：请准备本人有效证件和业务预约编号。'),
      });
      // Hold the initial detail until parsing finishes, then deliver its earlier queued
      // snapshot. The following list already says ready; the selected preview must still poll.
      let initialDetail = true;
      const detailRoute = /\/api\/v1\/workspace\/libraries\/[^/]+\/documents\/[^/?]+$/;
      const staleInitialDetail = async (route) => {
        if (route.request().method() !== 'GET' || !initialDetail) return route.continue();
        initialDetail = false;
        let response = await route.fetch();
        let job = await response.json();
        for (
          let attempt = 0;
          ['queued', 'parsing'].includes(job.state) && attempt < 100;
          attempt++
        ) {
          await new Promise((resolve) => setTimeout(resolve, 100));
          response = await route.fetch();
          job = await response.json();
        }
        expect(job.state).toBe('ready');
        await route.fulfill({
          response,
          json: { ...job, state: 'queued', progress: 0, segments: [] },
        });
      };
      await page.route(detailRoute, staleInitialDetail);
      try {
        await page.getByRole('button', { name: '开始解析', exact: true }).click();
        await expect(page.getByLabel('分段正文1', { exact: true })).toBeVisible({ timeout: 30000 });
      } finally {
        await page.unroute(detailRoute, staleInitialDetail);
      }
      await page.getByLabel('分段标题1', { exact: true }).fill('办理材料说明');
      await page
        .getByLabel('分段正文1', { exact: true })
        .fill('办理材料要求：准备本人证件和预约编号，具体以人工核验为准。');
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({
        path: 'target/platform-completion/knowledge-segments.png',
        fullPage: true,
      });
      // Finish the confirmation refresh after the user selects another tab.
      // A background reload must preserve that newer selection.
      const library = new URL(page.url()).searchParams.get('library');
      const reloadRoute = new RegExp('/api/v1/workspace/libraries/' + library + '$');
      let releaseRefresh;
      let heldRefresh = false;
      const refreshGate = new Promise((resolve) => {
        releaseRefresh = resolve;
      });
      const delayedRefresh = async (route) => {
        if (route.request().method() !== 'GET' || heldRefresh) return route.continue();
        heldRefresh = true;
        const response = await route.fetch();
        await refreshGate;
        await route.fulfill({ response });
      };
      await page.route(reloadRoute, delayedRefresh);
      try {
        await page.getByRole('button', { name: '确认分段并生成草稿', exact: true }).click();
        await expect(
          page.getByText(
            '已生成草稿。请回到知识条目核对，再发布知识库。重复确认不会创建第二份条目。',
            { exact: true },
          ),
        ).toBeVisible();
        await page.getByRole('tab', { name: '知识条目', exact: true }).click();
        releaseRefresh();
        await expect(page.getByRole('button', { name: '刷新知识库', exact: true })).not.toHaveClass(
          /is-loading/,
        );
        expect(heldRefresh).toBe(true);
        await expect(page.getByRole('row').filter({ hasText: '办理材料说明' })).toBeVisible();
      } finally {
        releaseRefresh();
        await page.unroute(reloadRoute, delayedRefresh);
      }
      await page.reload();
      await expect(page.getByRole('row').filter({ hasText: '办理材料说明' })).toBeVisible();
      await page.getByRole('button', { name: '发布知识库', exact: true }).click();
      await expect(page.getByText('已发布 v1', { exact: true })).toBeVisible();
    },
  );
  await check(
    'business debug three columns recover turns and missed questions enter review queue',
    async () => {
      let releaseHistory;
      let heldHistory = false;
      const historyGate = new Promise((resolve) => {
        releaseHistory = resolve;
      });
      const historyRoute = /\/api\/v1\/workspace\/debug-sessions$/;
      const delayedHistory = async (route) => {
        if (route.request().method() !== 'GET' || heldHistory) return route.continue();
        heldHistory = true;
        const response = await route.fetch();
        await historyGate;
        await route.fulfill({ response });
      };
      await page.route(historyRoute, delayedHistory);
      try {
        await go('/admin/business-debug', '业务调试');
        await select('调试话术').click();
        await page.getByRole('option', { name: 'Vue浏览器话术 · v1', exact: true }).click();
        await page.getByLabel('调试名称', { exact: true }).fill('浏览器业务调试');
        releaseHistory();
        await expect(
          page.getByRole('button', { name: '新建调试会话', exact: true }),
        ).not.toHaveClass(/is-loading/);
        expect(heldHistory).toBe(true);
        await expect(
          page.locator('.el-select__selected-item').filter({ hasText: 'Vue浏览器话术 · v1' }),
        ).toBeVisible();
      } finally {
        releaseHistory();
        await page.unroute(historyRoute, delayedHistory);
      }
      await page.getByRole('button', { name: '新建调试会话', exact: true }).click();
      await page.getByLabel('业务调试输入', { exact: true }).fill('贷款利率优惠是多少？');
      await page.getByRole('button', { name: '发送调试输入', exact: true }).click();
      await expect(page.getByText('客户：贷款利率优惠是多少？', { exact: true })).toBeVisible();
      await page.reload();
      await expect(page.getByText('客户：贷款利率优惠是多少？', { exact: true })).toBeVisible();
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({
        path: 'target/platform-completion/business-debug.png',
        fullPage: true,
      });
      await page.getByRole('button', { name: '查看待补知识', exact: true }).click();
      await select('问题来源').click();
      await page.getByRole('option', { name: '测试会话', exact: true }).click();
      await page.getByRole('button', { name: '筛选问题', exact: true }).click();
      await expect(page.getByRole('row').filter({ hasText: '贷款利率优惠是多少？' })).toBeVisible();
      await page
        .getByRole('row')
        .filter({ hasText: '贷款利率优惠是多少？' })
        .getByRole('button', { name: '查看来源证据', exact: true })
        .click();
      await expect(page.getByRole('heading', { name: '业务调试', exact: true })).toBeVisible();
      await expect(page.getByText('客户：贷款利率优惠是多少？', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: '结束会话', exact: true }).click();
      await expect(page.getByLabel('业务调试输入', { exact: true })).toBeDisabled();
    },
  );
  await check('skill dictionary and configured gateway connection save and reload', async () => {
    await go('/admin/operations/teams', '团队与数据权限');
    await page.getByRole('button', { name: '新增技能', exact: true }).click();
    await page.getByLabel('技能名称', { exact: true }).fill('浏览器咨询');
    await page.getByRole('button', { name: '保存技能', exact: true }).click();
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: '浏览器咨询' })).toBeVisible();
    await go('/admin/lines', '线路与号码');
    await page.getByRole('button', { name: '新增连接', exact: true }).click();
    await page.getByLabel('连接名称', { exact: true }).fill('浏览器网关连接');
    await page.getByLabel('连接网关地址', { exact: true }).fill('https://gateway.example.com');
    await page.getByLabel('连接凭据引用', { exact: true }).fill('BROWSER_GATEWAY');
    await page.getByRole('button', { name: '保存连接', exact: true }).click();
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: '浏览器网关连接' })).toBeVisible();
  });
  await check(
    'billing usage and saved estimate assumptions preserve unknown versus free',
    async () => {
      await go('/admin/billing', '用量与费用');
      await page.getByRole('tab', { name: '费用估算与口径', exact: true }).click();
      await page.getByLabel('估算价格来源', { exact: true }).fill('浏览器内部报价V1');
      await page.getByLabel('估算价格有效期起', { exact: true }).fill('2026-11-01');
      await page.getByLabel('估算价格有效期止', { exact: true }).fill('2026-11-30');
      await page.getByRole('button', { name: '保存估算口径', exact: true }).click();
      await page.reload();
      await page.getByRole('tab', { name: '费用估算与口径', exact: true }).click();
      await expect(page.getByLabel('估算价格来源', { exact: true })).toHaveValue(
        '浏览器内部报价V1',
      );
      await expect(
        page.getByText(
          '单价未填写，费用显示为未计价。请输入所有单价后查看估算；明确免费项目请填0。',
          { exact: true },
        ),
      ).toBeVisible();
    },
  );

  await check(
    'SMS task reuses variable template import previews saves reloads and starts separately',
    async () => {
      await page.evaluate(async () => {
        const headers = {
          Authorization: 'Bearer ' + sessionStorage.getItem('ai-platform-access-token'),
          'Content-Type': 'application/json',
        };
        for (const [kind, name, payload] of [
          ['product', '短信工作流产品', { form: 'sms', unit_price: 1000 }],
          ['sender', '短信工作流签名', { brand: '测试企业' }],
          ['template', '短信工作流模板', { form: 'sms', body: '您好，您的权益为{{offer}}。' }],
        ]) {
          let r = await fetch('/api/v1/admin/messaging/resources/' + kind, {
            method: 'POST',
            headers,
            body: JSON.stringify({ name, payload }),
          });
          if (!r.ok) throw Error(await r.text());
          let v = await r.json();
          r = await fetch('/api/v1/admin/messaging/resources/' + v.id + '/state', {
            method: 'POST',
            headers,
            body: JSON.stringify({ state: 'APPROVED' }),
          });
          if (!r.ok) throw Error(await r.text());
        }
        const credit = await fetch('/api/v1/admin/messaging/sandbox-credit', {
          method: 'POST',
          headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
          body: JSON.stringify({ amount: 1000000 }),
        });
        if (!credit.ok) throw Error(await credit.text());
      });
      await go('/admin/sms/tasks', '短信发送任务');
      await page.getByRole('button', { name: '创建短信任务', exact: true }).click();
      await page.getByLabel('短信任务名称', { exact: true }).fill('短信变量浏览器验收');
      for (const [label, name] of [
        ['短信产品', '短信工作流产品'],
        ['短信模板', '短信工作流模板'],
        ['短信签名', '短信工作流签名'],
      ]) {
        await select(label).click();
        await page.getByRole('option', { name, exact: true }).click();
      }
      await page.getByRole('button', { name: '下一步', exact: true }).click();
      await page.getByRole('button', { name: '上传短信号码 / 下载变量模板', exact: true }).click();
      const downloading = page.waitForEvent('download');
      await page.getByRole('button', { name: '下载导入模板', exact: true }).click();
      expect((await downloading).suggestedFilename()).toBe('短信名单导入模板.xlsx');
      await page.getByLabel('上传号码文件', { exact: true }).setInputFiles({
        name: '短信变量名单.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(
          '手机号,姓名,联系授权状态,变量:offer\n13100000071,短信客户,已同意,服务权益\n',
        ),
      });
      await expect(page.getByText('导入预览（尚未保存）', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: '确认导入', exact: true }).click();
      await expect(page.getByText('导入结果', { exact: true })).toBeVisible({ timeout: 30000 });
      await page.getByRole('button', { name: '完成', exact: true }).click();
      await page.getByRole('button', { name: '预览有效名单', exact: true }).click();
      await expect(page.getByRole('row').filter({ hasText: '13100000071' })).toContainText(
        '服务权益',
      );
      await page.getByRole('button', { name: '保存草稿并预检', exact: true }).click();
      await expect(page.getByText('草稿已保存，尚未开始发送', { exact: true })).toBeVisible();
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: 'target/platform-completion/sms-review.png', fullPage: true });
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await page.reload();
      const row = page.getByRole('row').filter({ hasText: '短信变量浏览器验收' });
      await expect(row).toContainText('草稿');
      await row.getByRole('button', { name: '任务详情', exact: true }).click();
      await expect(page.getByRole('dialog')).toContainText('服务权益');
      await page.locator('.el-drawer__close-btn').click();
      await row.getByRole('button', { name: '预检并启动', exact: true }).click();
      await page.getByRole('button', { name: '确定', exact: true }).click();
      await expect(page.getByRole('dialog', { name: '短信任务详情', exact: true })).toContainText(
        '已提交 1 / 1',
        { timeout: 30000 },
      );
      await page.locator('.el-drawer__close-btn').click();
    },
  );

  await check(
    'renaming an unpublished collection field keeps the draft saveable and restores values',
    async () => {
      await go('/admin/scripts', '话术管理');
      const target = page
        .getByRole('row')
        .filter({ has: page.getByRole('cell', { name: 'Vue浏览器话术（副本）', exact: true }) });
      await target.getByRole('button', { name: '配置话术', exact: true }).click();
      await page.getByRole('tab', { name: '信息采集与合格条件', exact: true }).click();
      await page.getByRole('button', { name: '添加采集字段', exact: true }).click();
      await page.getByLabel('字段标识', { exact: true }).fill('old_key');
      await page.getByLabel('字段名称', { exact: true }).fill('服务需求');
      await page.getByLabel('询问话术', { exact: true }).fill('您需要了解哪项服务？');
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await page.getByRole('spinbutton', { name: '最多字符', exact: true }).fill('42');
      await page.getByLabel('字段标识', { exact: true }).fill('service_need');
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await page.getByRole('button', { name: '返回话术列表', exact: true }).click();
      await target.getByRole('button', { name: '配置话术', exact: true }).click();
      await page.getByRole('tab', { name: '信息采集与合格条件', exact: true }).click();
      await expect(page.getByLabel('字段标识', { exact: true })).toHaveValue('service_need');
      await expect(page.getByRole('spinbutton', { name: '最多字符', exact: true })).toHaveValue(
        '42',
      );
      await expect(page.getByRole('spinbutton', { name: '最多重问次数', exact: true })).toHaveValue(
        '2',
      );
    },
  );
}
