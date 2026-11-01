import { expect } from '@playwright/test';
import { mkdir } from 'node:fs/promises';

export async function marketingChecks({ page, origin, check, go }) {
  const row = (name) => page.getByRole('row').filter({ hasText: name });
  const dialog = () => page.getByRole('dialog').filter({ visible: true });
  const close = async () => {
    await dialog()
      .locator('button.el-dialog__headerbtn,button.el-drawer__close-btn')
      .first()
      .click();
    await expect(dialog()).toHaveCount(0);
  };
  const api = async (path, method = 'GET', data) => {
    const token = await page.evaluate(() => sessionStorage.getItem('ai-platform-access-token'));
    const response = await page.request.fetch(origin + '/api/v1/marketing/' + path, {
      method,
      data,
      headers: { Authorization: 'Bearer ' + token },
    });
    expect(response.ok(), await response.text()).toBeTruthy();
    return response.json();
  };
  const choose = async (field, name, multiple = false) => {
    await field.click();
    await page.getByRole('option').filter({ hasText: name }).last().click();
    if (multiple) await page.keyboard.press('Escape');
  };
  await mkdir('target/marketing-browser', { recursive: true });

  await check('marketing datasets create upload deduplicate masked detail and reload', async () => {
    await go('/admin/marketing/datasets', '数据包与撞库');
    await page.getByRole('button', { name: '新建数据包', exact: true }).click();
    await dialog().getByLabel('名称', { exact: true }).fill('浏览器营销数据包');
    await dialog().getByLabel('说明', { exact: true }).fill('本地合成数据');
    await dialog().getByRole('button', { name: '创建', exact: true }).click();
    await expect(row('浏览器营销数据包')).toBeVisible();
    await page.getByRole('button', { name: '上传新版本', exact: true }).click();
    await dialog().getByRole('button', { name: '上传并导入', exact: true }).click();
    await expect(page.getByText('请选择 CSV 或 TXT 文件', { exact: true })).toBeVisible();
    await dialog()
      .locator('input[type=file]')
      .setInputFiles({
        name: 'browser-marketing.csv',
        mimeType: 'text/csv',
        buffer: Buffer.from(
          '手机号,地区\n13100000101,浙江\n13100000102,上海\n13100000101,浙江\ninvalid,北京\n',
        ),
      });
    await dialog()
      .getByLabel('授权凭证编号（提交撞库前必须填写）', { exact: true })
      .fill('SYNTHETIC-BROWSER-AUTH');
    await dialog().getByRole('button', { name: '上传并导入', exact: true }).click();
    await expect(dialog()).toHaveCount(0);
    await expect(row('browser-marketing.csv')).toContainText('已就绪');
    await row('browser-marketing.csv')
      .getByRole('button', { name: '明细与撞库', exact: true })
      .click();
    await expect(dialog()).toContainText('131****0101');
    await expect(dialog()).toContainText('重复');
    await expect(dialog()).not.toContainText('13100000101');
    await close();
    await page.reload();
    await expect(row('浏览器营销数据包')).toBeVisible();
    for (const tab of ['合作方', '撞库任务', '数据包']) {
      await page.getByRole('tab', { name: tab, exact: true }).click();
      await expect(page.getByRole('tabpanel').filter({ visible: true })).toBeVisible();
    }
    await page.screenshot({ path: 'target/marketing-browser/datasets.png', fullPage: true });
  });

  await check('marketing segment preview save echo freeze snapshot and task entry', async () => {
    await go('/admin/marketing/segments', '人群圈选');
    await page.getByRole('button', { name: '新建人群', exact: true }).click();
    await dialog().getByLabel('人群名称', { exact: true }).fill('浏览器营销人群');
    await choose(dialog().getByLabel('数据包版本', { exact: true }), '浏览器营销数据包', true);
    await dialog().getByRole('button', { name: '预览人数', exact: true }).click();
    await expect(dialog()).toContainText('预计圈选 2 人');
    await dialog().getByRole('button', { name: '保存', exact: true }).click();
    await row('浏览器营销人群').getByRole('button', { name: '编辑规则', exact: true }).click();
    await expect(dialog().getByLabel('人群名称', { exact: true })).toHaveValue('浏览器营销人群');
    await close();
    await row('浏览器营销人群').getByRole('button', { name: '生成快照', exact: true }).click();
    await dialog().getByRole('spinbutton').fill('0');
    await dialog().getByRole('button', { name: '生成', exact: true }).click();
    await expect(page.getByRole('button', { name: '创建外呼/短信任务', exact: true })).toHaveCount(
      1,
    );
    await page.getByRole('button', { name: '创建外呼/短信任务', exact: true }).click();
    await expect(dialog()).toContainText('实验组 2 人');
    await expect(dialog().getByRole('button', { name: '创建并开始', exact: true })).toBeVisible();
    await close();
    await page.reload();
    await expect(row('浏览器营销人群').first()).toBeVisible();
    await page.screenshot({ path: 'target/marketing-browser/segments.png', fullPage: true });
  });

  await check('marketing link domain form whitelist persistence and all six tabs', async () => {
    await go('/admin/marketing/links', '短链与域名');
    await page.getByRole('button', { name: '添加域名', exact: true }).click();
    await dialog().getByLabel('短链域名', { exact: true }).fill('browser-links.example.test');
    await dialog().getByRole('button', { name: '保存', exact: true }).click();
    await expect(row('browser-links.example.test')).toBeVisible();
    await page.getByRole('tab', { name: '落地页白名单', exact: true }).click();
    await page
      .getByPlaceholder('落地页域名，如 www.example.com')
      .fill('browser-landing.example.test');
    await page.getByRole('button', { name: '加入白名单', exact: true }).click();
    await expect(row('browser-landing.example.test')).toBeVisible();
    for (const name of ['效果统计', '点击明细', '活动公共链接', '回执映射', '域名池']) {
      await page.getByRole('tab', { name, exact: true }).click();
      await expect(page.getByRole('tabpanel').filter({ visible: true })).toBeVisible();
    }
    await page.reload();
    await expect(row('browser-links.example.test')).toBeVisible();
    await page.screenshot({ path: 'target/marketing-browser/links.png', fullPage: true });
  });

  await check('marketing outcome rule draft saves echoes and all result views load', async () => {
    await go('/admin/marketing/outcomes', '触达结果判定');
    await page.getByRole('tab', { name: '判定规则', exact: true }).click();
    await page.getByRole('button', { name: '基于当前版本新建草稿', exact: true }).click();
    await page.getByLabel('版本说明', { exact: true }).fill('浏览器验证规则草稿');
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await page.reload();
    await page.getByRole('tab', { name: '判定规则', exact: true }).click();
    await expect(page.getByLabel('版本说明', { exact: true })).toHaveValue('浏览器验证规则草稿');
    for (const name of ['判定结果', '结果分布', '待复核', '判定准确率']) {
      await page.getByRole('tab').filter({ hasText: name }).click();
      await expect(page.getByRole('tabpanel').filter({ visible: true })).toBeVisible();
    }
    await page.screenshot({ path: 'target/marketing-browser/outcomes.png', fullPage: true });
  });

  await check(
    'marketing journey edit publish snapshot selection estimate start pause resume stop',
    async () => {
      const journey = await api('journeys', 'POST', {
        name: '浏览器等待活动',
        definition: {
          nodes: [
            { id: 'wait', type: 'wait', hours: 24, next: 'end' },
            { id: 'end', type: 'end' },
          ],
        },
      });
      await go('/admin/marketing/journeys', '营销活动编排');
      await expect(page.getByText('数据模块未接入', { exact: false })).toHaveCount(0);
      await row(journey.name).getByRole('button', { name: '编辑 / 启动', exact: true }).click();
      await dialog().getByLabel('说明', { exact: true }).fill('浏览器保存回显');
      await dialog().getByRole('button', { name: '保存并发布', exact: true }).click();
      await choose(
        dialog().locator('.el-select').filter({ hasText: '选择人群快照' }).getByRole('combobox'),
        '浏览器营销人群',
      );
      await dialog().getByRole('button', { name: '预估触达与成本', exact: true }).click();
      await expect(dialog()).toContainText('快照人数');
      await dialog().getByRole('button', { name: '启动活动', exact: true }).click();
      await page.getByRole('button', { name: '确定', exact: true }).click();
      await expect(page.getByRole('dialog', { name: '确认', exact: true })).toBeHidden();
      await expect(dialog().getByRole('button', { name: '暂停', exact: true })).toBeVisible();
      await dialog().getByRole('button', { name: '暂停', exact: true }).click();
      await dialog().getByRole('button', { name: '继续', exact: true }).click();
      await dialog().getByRole('button', { name: '停止', exact: true }).click();
      await page.getByRole('button', { name: '确定', exact: true }).click();
      await expect(page.getByRole('dialog', { name: '确认', exact: true })).toBeHidden();
      await expect(dialog()).toContainText('已停止');
      await close();
      await page.reload();
      await expect(row(journey.name)).toContainText('已停止');
      await page.screenshot({ path: 'target/marketing-browser/journeys.png', fullPage: true });
    },
  );

  await check('marketing profile masked lookup empty state and funnel refresh', async () => {
    await go('/admin/marketing/profiles', '用户触达档案');
    await page.getByPlaceholder('输入手机号，例如 13800138000').fill('13100000101');
    await page.getByRole('button', { name: '查询档案', exact: true }).click();
    await expect(page.getByText('暂无触达记录（阶段：待挖掘）', { exact: true })).toBeVisible();
    await page.screenshot({ path: 'target/marketing-browser/profiles.png', fullPage: true });
    await go('/admin/marketing/dashboard', '营销漏斗看板');
    await page.getByRole('button', { name: '重新汇总所选日期', exact: true }).click();
    await expect(page.getByText('已重新汇总', { exact: true })).toBeVisible();
    await expect(page.getByText('短信送达率', { exact: true })).toBeVisible();
    await page.screenshot({ path: 'target/marketing-browser/dashboard.png', fullPage: true });
    await page.goBack();
    await expect(page).toHaveURL(origin + '/admin/marketing/profiles');
  });
}
