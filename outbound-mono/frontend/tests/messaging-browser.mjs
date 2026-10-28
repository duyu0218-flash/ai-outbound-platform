import { expect } from '@playwright/test';
import { fileURLToPath } from 'node:url';
export async function messagingChecks({ page, origin, check }) {
  const tab = async (name) => {
    await page.getByRole('tab', { name, exact: true }).click();
  };
  const choose = async (label, option) => {
    await page
      .getByRole('combobox', { name: label, exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option', { name: option, exact: true }).click();
    await page.keyboard.press('Escape');
  };
  const row = (name) => page.getByRole('row').filter({ hasText: name });
  const saved = async () => {
    await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeEnabled();
  };
  let contact;
  await check('messaging independent entrance resource forms approval persistence', async () => {
    await page.getByRole('menuitem').filter({ hasText: '消息营销' }).click();
    await expect(page).toHaveURL(origin + '/admin/messaging');
    await expect(page.getByRole('heading', { name: '消息营销', exact: true })).toBeVisible();
    await expect(page.getByText(/当前为模拟环境/)).toBeVisible();
    await tab('内容与主体');
    for (const [kind, name, field, value] of [
      ['消息产品', '浏览器消息产品', null, null],
      ['发送主体', '浏览器消息主体', '品牌主体', '测试品牌'],
      ['消息模板', '浏览器消息模板', '模板正文', '您好，{{name}}。'],
    ]) {
      await choose('资源类型', kind);
      await page.getByRole('textbox', { name: '资源名称', exact: true }).fill(name);
      if (field) await page.getByRole('textbox', { name: field, exact: true }).fill(value);
      await page.getByRole('button', { name: '保存草稿', exact: true }).click();
      await expect(row(name)).toBeVisible();
      await saved();
      await row(name).getByRole('button', { name: '批准', exact: true }).click();
      await expect(row(name)).toContainText('APPROVED');
      await saved();
    }
    await page.reload();
    await expect(row('浏览器消息模板')).toContainText('APPROVED');
    await row('浏览器消息模板').getByRole('button', { name: '查看内容', exact: true }).click();
    await expect(page.getByText('您好，{{name}}。', { exact: false }).last()).toBeVisible();
    await page.keyboard.press('Escape');
    contact = await page.evaluate(async () => {
      const response = await fetch('/api/v1/contacts?keyword=13100000002', {
        headers: { Authorization: 'Bearer ' + sessionStorage.getItem('ai-platform-access-token') },
      });
      return (await response.json())[0];
    });
    expect(contact.id).toBeTruthy();
  });
  await check('messaging sandbox credit send validation delivery detail refund', async () => {
    await tab('账户与账本');
    await page.getByRole('button', { name: '添加模拟额度', exact: true }).click();
    await expect(page.getByText('模拟额度已入账', { exact: true })).toBeVisible();
    await saved();
    await tab('发送消息');
    await page.getByRole('button', { name: '提交模拟发送', exact: true }).click();
    await expect(page.getByText('请选择消息产品', { exact: true })).toBeVisible();
    await choose('消息产品', '浏览器消息产品');
    await choose('发送主体', '浏览器消息主体');
    await choose('消息模板', '浏览器消息模板');
    await page.getByRole('textbox', { name: '接收号码', exact: true }).fill('+8613100000002');
    await page
      .getByRole('textbox', { name: '授权联系人编号', exact: true })
      .fill(String(contact.id));
    await page.getByRole('textbox', { name: '发送模板变量', exact: true }).fill('{"name":"客户"}');
    await page.getByRole('button', { name: '提交模拟发送', exact: true }).click();
    await expect(page.getByText(/已受理模拟消息/)).toBeVisible();
    await saved();
    await tab('发送记录');
    await expect
      .poll(async () => {
        await page.getByRole('button', { name: '刷新', exact: true }).click();
        await saved();
        return page.locator('.el-table__body').first().innerText();
      })
      .toContain('DELIVERED');
    await page.getByRole('button', { name: '详情', exact: true }).first().click();
    await expect(page.getByRole('heading', { name: '消息详情', exact: true })).toBeVisible();
    await expect(page.getByText('message.delivered', { exact: true })).toBeVisible();
    await saved();
    await page.getByRole('button', { name: '返回列表', exact: true }).click();
    await expect(page.getByRole('heading', { name: '消息详情', exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: '模拟退款', exact: true }).first().click();
    await expect(page.getByText('REFUNDED', { exact: true })).toBeVisible();
  });
  await check(
    'messaging application grants one-time secret revocation and browser history',
    async () => {
      await tab('应用接入');
      await page.getByRole('textbox', { name: '应用名称', exact: true }).fill('浏览器下游应用');
      for (const [label, value] of [
        ['授权产品', '浏览器消息产品'],
        ['授权主体', '浏览器消息主体'],
        ['授权模板', '浏览器消息模板'],
      ])
        await choose(label, value);
      await page.getByRole('button', { name: '创建应用', exact: true }).click();
      await expect(
        page.getByRole('button', { name: '已保存，隐藏密钥', exact: true }),
      ).toBeVisible();
      await saved();
      await page.getByRole('button', { name: '已保存，隐藏密钥', exact: true }).click();
      await expect(page.getByRole('button', { name: '已保存，隐藏密钥', exact: true })).toHaveCount(
        0,
      );
      await row('浏览器下游应用').getByRole('button', { name: '撤销应用', exact: true }).click();
      await expect(
        row('浏览器下游应用').getByRole('button', { name: '撤销应用', exact: true }),
      ).toHaveCount(0);
      await tab('人群与活动');
      await expect(
        page.getByRole('heading', { name: '新建人群与活动', exact: true }),
      ).toBeVisible();
      await page.goBack();
      await expect(page).toHaveURL(/section=developers/);
    },
  );
  await check('messaging scheduled cancellation and call rule approval disable', async () => {
    await tab('发送消息');
    await choose('消息产品', '浏览器消息产品');
    await choose('发送主体', '浏览器消息主体');
    await choose('消息模板', '浏览器消息模板');
    await page.getByRole('textbox', { name: '接收号码', exact: true }).fill('+8613100000002');
    await page
      .getByRole('textbox', { name: '授权联系人编号', exact: true })
      .fill(String(contact.id));
    await page
      .getByRole('textbox', { name: '发送模板变量', exact: true })
      .fill('{"name":"预约客户"}');
    const later = await page.evaluate(() => {
      const d = new Date(Date.now() + 3600000);
      const p = (n) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
    });
    await page.getByRole('textbox', { name: '计划发送时间', exact: true }).fill(later);
    await page.getByRole('button', { name: '提交模拟发送', exact: true }).click();
    await expect(page.getByText(/已受理模拟消息/)).toBeVisible();
    await saved();
    await tab('发送记录');
    await page.getByRole('button', { name: '取消', exact: true }).first().click();
    await expect(page.getByText('CANCELLED', { exact: true })).toBeVisible();
    await saved();
    await tab('内容与主体');
    await choose('资源类型', '外呼联动规则');
    await page.getByRole('textbox', { name: '资源名称', exact: true }).fill('浏览器外呼联动');
    for (const [label, value] of [
      ['活动消息产品', '浏览器消息产品'],
      ['活动发送主体', '浏览器消息主体'],
      ['活动消息模板', '浏览器消息模板'],
    ])
      await choose(label, value);
    await page
      .getByRole('textbox', { name: '活动模板变量', exact: true })
      .fill('{"name":"外呼客户"}');
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(row('浏览器外呼联动')).toBeVisible();
    await saved();
    await row('浏览器外呼联动').getByRole('button', { name: '批准', exact: true }).click();
    await expect(row('浏览器外呼联动')).toContainText('APPROVED');
    await saved();
    await row('浏览器外呼联动').getByRole('button', { name: '停用', exact: true }).click();
    await expect(row('浏览器外呼联动')).toContainText('DISABLED');
    await saved();
  });
  await check('messaging audience campaign launch interaction report and suppression', async () => {
    await tab('人群与活动');
    await choose('资源类型', '客户人群');
    await page.getByRole('textbox', { name: '资源名称', exact: true }).fill('浏览器客户人群');
    await page
      .getByRole('textbox', { name: '人群联系人编号', exact: true })
      .fill(String(contact.id));
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(row('浏览器客户人群')).toBeVisible();
    await saved();
    await row('浏览器客户人群').getByRole('button', { name: '批准', exact: true }).click();
    await expect(row('浏览器客户人群')).toContainText('APPROVED');
    await saved();
    await choose('资源类型', '营销活动');
    await page.getByRole('textbox', { name: '资源名称', exact: true }).fill('浏览器模拟活动');
    for (const [label, value] of [
      ['活动客户人群', '浏览器客户人群'],
      ['活动消息产品', '浏览器消息产品'],
      ['活动发送主体', '浏览器消息主体'],
      ['活动消息模板', '浏览器消息模板'],
    ])
      await choose(label, value);
    await page
      .getByRole('textbox', { name: '活动模板变量', exact: true })
      .fill('{"name":"活动客户"}');
    await page.getByRole('button', { name: '保存草稿', exact: true }).click();
    await expect(row('浏览器模拟活动')).toBeVisible();
    await saved();
    await row('浏览器模拟活动').getByRole('button', { name: '批准', exact: true }).click();
    await expect(row('浏览器模拟活动')).toContainText('APPROVED');
    await saved();
    await row('浏览器模拟活动').getByRole('button', { name: '启动模拟活动', exact: true }).click();
    await expect(page.getByRole('button', { name: '刷新批次', exact: true })).toBeVisible();
    await saved();
    await expect
      .poll(async () => {
        await page.getByRole('button', { name: '刷新批次', exact: true }).click();
        await saved();
        return page.locator('body').innerText();
      })
      .toContain('ACCEPTED');
    await tab('互动与成效');
    await page
      .getByRole('combobox', { name: '互动消息', exact: true })
      .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]')
      .click();
    await page.getByRole('option').filter({ hasText: 'msg_' }).first().click();
    await page.getByRole('textbox', { name: '回复内容', exact: true }).fill('请稍后联系我');
    await page.getByRole('button', { name: '记录模拟互动', exact: true }).click();
    await expect(page.getByText('模拟互动已保存', { exact: true })).toBeVisible();
    await saved();
    await expect(page.getByText('SANDBOX', { exact: true }).first()).toBeVisible();
    await page.getByRole('heading', { name: '互动记录', exact: true }).click();
    await page.screenshot({
      path: fileURLToPath(new URL('../../target/messaging-browser.png', import.meta.url)),
      fullPage: true,
    });
    await tab('发送记录');
    await page
      .getByRole('textbox', { name: '退订联系人编号', exact: true })
      .fill(String(contact.id));
    await page.getByRole('textbox', { name: '退订原因', exact: true }).fill('客户要求退订');
    await page.getByRole('button', { name: '保存退订', exact: true }).click();
    await expect(page.getByText('退订已保存，后续发送将被阻止', { exact: true })).toBeVisible();
    await saved();
    await tab('发送消息');
    await choose('消息产品', '浏览器消息产品');
    await choose('发送主体', '浏览器消息主体');
    await choose('消息模板', '浏览器消息模板');
    await page.getByRole('textbox', { name: '接收号码', exact: true }).fill('+8613100000002');
    await page
      .getByRole('textbox', { name: '授权联系人编号', exact: true })
      .fill(String(contact.id));
    await page.getByRole('textbox', { name: '发送模板变量', exact: true }).fill('{"name":"客户"}');
    await page.getByRole('button', { name: '提交模拟发送', exact: true }).click();
    await expect(page.getByText(/已退订/)).toBeVisible();
  });
}
