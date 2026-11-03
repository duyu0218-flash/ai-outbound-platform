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
}
