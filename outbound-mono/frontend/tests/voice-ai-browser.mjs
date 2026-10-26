import { chromium, expect } from '@playwright/test';
import { writeFile } from 'node:fs/promises';
const origin = process.env.MONO_BROWSER_URL;
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
const errors = [],
  checks = [];
page.on('pageerror', (e) => errors.push(e.message));
async function check(name, work) {
  await work();
  checks.push(name);
  console.log('PASS ' + name);
}
const select = (label) =>
  page
    .getByRole('combobox', { name: label, exact: true })
    .locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]');
async function login(role) {
  await page.goto(origin + '/admin/login');
  await page.getByLabel('用户名', { exact: true }).fill('voice-browser-' + role);
  await page.getByLabel('密码', { exact: true }).fill('SyntheticVoicePass123!');
  await page.getByRole('button', { name: '登录', exact: true }).click();
}
try {
  await check('login and menu entry', async () => {
    await login('admin');
    await page.getByRole('menuitem').filter({ hasText: '语音方案对比' }).click();
    await expect(page).toHaveURL(origin + '/admin/voice-ai');
    await expect(page.getByRole('heading', { name: '语音方案对比' })).toBeVisible();
    await expect(page.getByText('缺少配置', { exact: true })).toHaveCount(6);
    await expect(
      page.getByTestId('voice-auth-modes').filter({ hasText: 'ASR：API Key；TTS：API Key' }),
    ).toHaveCount(1);
    await expect(
      page.getByText(
        'VOICE_AI_VOLC_ASR_API_KEY、VOICE_AI_VOLC_TTS_API_KEY、VOICE_AI_VOLC_LLM_API_KEY、VOICE_AI_VOLC_LLM_MODEL',
        { exact: true },
      ),
    ).toBeVisible();
  });
  await check('six credential panels save masked values and clear after reload', async () => {
    for (const [name, key] of [
      ['阿里云三段式', 'DASHSCOPE_API_KEY'],
      ['千问端到端实时语音', 'DASHSCOPE_API_KEY'],
      ['火山引擎三段式', 'VOICE_AI_VOLC_ASR_API_KEY'],
      ['豆包端到端实时语音', 'VOICE_AI_VOLC_REALTIME_API_KEY'],
      ['腾讯云三段式', 'VOICE_AI_TENCENT_LLM_API_KEY'],
      ['百度端到端实时语音', 'VOICE_AI_BAIDU_API_KEY'],
    ]) {
      await select('凭据配置方案').click();
      await page.getByRole('option', { name, exact: true }).click();
      await page.getByLabel(key, { exact: true }).fill('ui-synthetic-private-key');
      await page.getByRole('button', { name: '加密保存供应商配置', exact: true }).click();
      await expect(page.getByLabel(key, { exact: true })).toHaveValue('');
      await page.reload();
      await select('凭据配置方案').click();
      await page.getByRole('option', { name, exact: true }).click();
      await expect(page.getByLabel(key, { exact: true })).toHaveAttribute(
        'placeholder',
        '已配置（隐藏），留空保持不变',
      );
      await expect(page.locator('body')).not.toContainText('ui-synthetic-private-key');
      await page.getByRole('button', { name: '清空 ' + key, exact: true }).click();
      await page.getByRole('button', { name: '加密保存供应商配置', exact: true }).click();
      await expect(page.getByRole('button', { name: '清空 ' + key, exact: true })).toHaveCount(0);
    }
    await expect(page.getByText('缺少配置', { exact: true })).toHaveCount(6);
  });
  await check('route and provider switching persists after reload', async () => {
    await select('技术路线').click();
    await page.getByRole('option', { name: '端到端实时语音', exact: true }).click();
    await select('供应商组合').click();
    await page.getByRole('option', { name: '百度端到端实时语音', exact: true }).click();
    await select('端到端播放方式').click();
    await page.getByRole('option', { name: '完整短句审核后播放', exact: true }).click();
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await expect(page.getByTestId('active-voice-profile')).toHaveText('百度端到端实时语音');
    await page.reload();
    await expect(page.getByTestId('active-voice-profile')).toHaveText('百度端到端实时语音');
    await expect(select('端到端播放方式')).toContainText('完整短句审核后播放');
    await select('技术路线').click();
    await page.getByRole('option', { name: '三段式 ASR → 模型 → TTS', exact: true }).click();
    await select('供应商组合').click();
    await page.getByRole('option', { name: '腾讯云三段式', exact: true }).click();
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await expect(page.getByTestId('active-voice-profile')).toHaveText('腾讯云三段式');
  });
  await check('frozen turn tuning saves reloads and rejects invalid combinations', async () => {
    await page.getByRole('spinbutton', { name: '短句判停毫秒', exact: true }).fill('500');
    await page.getByRole('spinbutton', { name: '数字未完句判停毫秒', exact: true }).fill('900');
    await page
      .getByRole('textbox', { name: 'AI身份告知', exact: true })
      .fill('您好，我是AI智能语音助手。');
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await page.reload();
    await expect(page.getByRole('spinbutton', { name: '短句判停毫秒', exact: true })).toHaveValue(
      '500',
    );
    await expect(
      page.getByRole('spinbutton', { name: '数字未完句判停毫秒', exact: true }),
    ).toHaveValue('900');
    const direct = page.getByRole('checkbox', { name: '按语音端点直接回复', exact: true });
    await direct.locator('xpath=ancestor::label[1]').click();
    await expect(direct).toBeChecked();
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await expect(
      page.getByText('按语音端点直接回复只适用于端到端逐句审核播放', { exact: true }),
    ).toBeVisible();
    await direct.locator('xpath=ancestor::label[1]').click();
    await expect(direct).not.toBeChecked();
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await page.getByRole('button', { name: '准备身份告知音频', exact: true }).click();
    await expect(page.getByText(/voice AI configuration missing/)).toBeVisible();
  });
  await check('optimization options save reload and reject biased audit parameters', async () => {
    await page.getByRole('spinbutton', { name: '最近对话记录条数', exact: true }).fill('8');
    await page.getByRole('spinbutton', { name: '历史文字总预算', exact: true }).fill('3500');
    await page
      .getByRole('textbox', { name: '客户识别词表参数', exact: true })
      .fill('{"aliyun":{"vocabulary_id":"browser-vocabulary"}}');
    await page
      .getByRole('textbox', { name: '输出审核识别参数', exact: true })
      .fill('{"aliyun":{"asr_model":"fun-asr-realtime"}}');
    await select('话轮聚合').click();
    await page.getByRole('option', { name: '三段式灰度聚合', exact: true }).click();
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await page.reload();
    await expect(
      page.getByRole('spinbutton', { name: '最近对话记录条数', exact: true }),
    ).toHaveValue('8');
    await expect(page.getByRole('spinbutton', { name: '历史文字总预算', exact: true })).toHaveValue(
      '3500',
    );
    await expect(page.getByRole('textbox', { name: '客户识别词表参数', exact: true })).toHaveValue(
      '{"aliyun":{"vocabulary_id":"browser-vocabulary"}}',
    );
    await expect(select('话轮聚合')).toContainText('三段式灰度聚合');
    await page
      .getByRole('textbox', { name: '输出审核识别参数', exact: true })
      .fill('{"aliyun":{"vocabulary_id":"not-independent"}}');
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await expect(
      page.getByRole('alert').filter({ hasText: '识别参数不合法或不支持' }),
    ).toBeVisible();
    await page.getByRole('textbox', { name: '输出审核识别参数', exact: true }).fill('{}');
    await page.getByRole('textbox', { name: '客户识别词表参数', exact: true }).fill('{}');
    await select('话轮聚合').click();
    await page.getByRole('option', { name: '观察（保留当前触发方式）', exact: true }).click();
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    await page.reload();
    await expect(select('话轮聚合')).toContainText('观察');
    await expect(
      page.getByRole('checkbox', { name: '并行准备语音合成', exact: true }),
    ).toBeChecked();
  });
  await check('snapshot audio set saved and reloaded', async () => {
    await page.getByLabel('样本组名称', { exact: true }).fill('浏览器统一音频样本');
    await page.getByLabel('统一话术', { exact: true }).fill('固定话术：请简短介绍服务，不作承诺。');
    await page
      .getByLabel('WAV 音频样本', { exact: true })
      .setInputFiles(process.env.VOICE_AI_TEST_WAV);
    await page.getByRole('button', { name: '固定话术并保存样本', exact: true }).click();
    await expect(page.getByTestId('voice-script-sha')).toContainText(/话术校验值：[a-f0-9]{64}/);
    await page.getByRole('button', { name: '运行所选组合', exact: true }).click();
    await expect(
      page.getByTestId('voice-comparison-results').getByText('未配置', { exact: true }),
    ).toHaveCount(6, { timeout: 20000 });
    await page.reload();
    await select('已保存样本组').click();
    await page.getByRole('option', { name: '浏览器统一音频样本', exact: true }).click();
    await expect(
      page.getByTestId('voice-comparison-results').getByText('未配置', { exact: true }),
    ).toHaveCount(6);
  });
  await check('authenticated audio preview loads on demand', async () => {
    await select('已保存样本组').click();
    await page.getByRole('option', { name: 'HTTP统一样本', exact: true }).click();
    await page.getByRole('button', { name: '加载试听', exact: true }).click();
    await expect(page.locator('audio')).toHaveAttribute('src', /^blob:/);
    await select('已保存样本组').click();
    await page.getByRole('option', { name: '浏览器统一音频样本', exact: true }).click();
  });
  await check('return path and restore legacy mode', async () => {
    await select('供应商组合').click();
    await page.getByRole('option', { name: '原有链路', exact: true }).click();
    await expect(select('供应商组合')).toContainText('原有链路');
    const saving = page.waitForResponse(
      (r) => r.url().endsWith('/api/v1/admin/settings/ai') && r.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: '保存通话方案', exact: true }).click();
    const saved = await saving;
    expect(saved.status()).toBe(200);
    expect(saved.request().postDataJSON().data.voice_backend).toBe('legacy');
    expect((await saved.json()).data?.voice_backend).toBe('legacy');
    await expect(page.getByTestId('active-voice-profile')).toHaveText('原有链路');
    await page.getByRole('button', { name: '返回系统设置', exact: true }).click();
    await expect(page).toHaveURL(origin + '/admin/settings');
    await page.getByRole('button', { name: '供应商与技术路线对比', exact: true }).click();
    await expect(page).toHaveURL(origin + '/admin/voice-ai');
  });
  await check('call details show frozen supplier and script fingerprint', async () => {
    await page.getByRole('menuitem').filter({ hasText: '通话记录' }).click();
    const row = page.getByRole('row').filter({ hasText: '13100000009' });
    await row.getByRole('button', { name: '事件', exact: true }).click();
    await expect(
      page.getByText('百度端到端实时语音 · 端到端实时语音', { exact: true }),
    ).toBeVisible();
    await page.locator('.el-drawer__close-btn').click();
    await page.getByRole('menuitem').filter({ hasText: '语音方案对比' }).click();
    await select('已保存样本组').click();
    await page.getByRole('option', { name: '浏览器统一音频样本', exact: true }).click();
    await expect(
      page.getByTestId('voice-comparison-results').getByText('未配置', { exact: true }),
    ).toHaveCount(6);
  });
  await page.screenshot({ path: 'target/voice-ai-browser.png', fullPage: true });
  await check('logout redirects protected page to login', async () => {
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await page.goto(origin + '/admin/voice-ai');
    await expect(page).toHaveURL(origin + '/admin/login');
  });
  expect(errors).toEqual([]);
  await writeFile(
    'target/voice-ai-browser-result.json',
    JSON.stringify({ checks, errors }, null, 2),
  );
} catch (error) {
  await page.screenshot({ path: 'target/voice-ai-browser-failure.png', fullPage: true });
  throw error;
} finally {
  await browser.close();
}
