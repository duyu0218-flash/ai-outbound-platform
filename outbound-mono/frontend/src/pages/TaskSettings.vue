<script setup>
import { ref, onMounted } from 'vue';
import { ops, perform } from '../operations';
import SettingSection from '../components/SettingSection.vue';
const model = ref(null),
  revision = ref(0),
  error = ref(''),
  busy = ref(false);
async function load() {
  try {
    const d = await ops('/benchmark/task-settings');
    model.value = d.draft;
    revision.value = d.revision;
    error.value = '';
  } catch (e) {
    error.value = e.message;
  }
}
async function save() {
  busy.value = true;
  try {
    await perform(
      () =>
        ops('/benchmark/task-settings', 'PUT', { config: model.value, revision: revision.value }),
      load,
    );
  } finally {
    busy.value = false;
  }
}
async function restore() {
  try {
    model.value = await ops('/benchmark/task-settings/defaults');
  } catch (e) {
    error.value = e.message;
  }
}
onMounted(load);
</script>
<template>
  <PageHeader title="任务设置" description="统一配置外呼日历、暂停窗口与号码触达频次"
    ><el-button @click="load">刷新</el-button></PageHeader
  >
  <el-alert v-if="error" type="error" :title="error" />
  <el-card v-if="model"
    ><el-tabs>
      <el-tab-pane label="外呼设置"
        ><el-form label-position="top">
          <el-form-item label="启用模式"
            ><el-radio-group v-model="model.mode"
              ><el-radio value="basic">基础模式</el-radio
              ><el-radio value="advanced">高级模式</el-radio></el-radio-group
            ></el-form-item
          >
          <el-form-item label="外呼时间"
            ><el-checkbox-group v-model="model.weekdays"
              ><el-checkbox
                v-for="(day, i) in ['周一', '周二', '周三', '周四', '周五', '周六', '周日']"
                :key="i"
                :value="i"
                >{{ day }}</el-checkbox
              ></el-checkbox-group
            ></el-form-item
          >
          <el-form-item label="时区"
            ><el-select v-model="model.timezone" aria-label="外呼时区"
              ><el-option
                v-for="z in ['Asia/Shanghai', 'Asia/Hong_Kong', 'UTC']"
                :key="z"
                :value="z"
                :label="z" /></el-select
          ></el-form-item>
          <section
            v-for="[key, label] in [
              ['windows', '外呼时间段'],
              ['pauses', '暂停时间段'],
            ]"
            :key="key"
          >
            <h3>{{ label }}</h3>
            <div v-for="(window, i) in model[key]" :key="i" class="toolbar">
              <el-input
                v-model="window.start"
                :aria-label="label + '开始' + i"
                placeholder="09:00"
              /><span>至</span
              ><el-input
                v-model="window.end"
                :aria-label="label + '结束' + i"
                placeholder="20:00"
              /><el-button @click="model[key].splice(i, 1)">移除</el-button>
            </div>
            <el-button
              :disabled="model[key].length >= 12"
              @click="model[key].push({ start: '12:00', end: '13:00' })"
              >添加{{ label }}</el-button
            >
          </section>
          <el-form-item label="自动启动呼叫任务"
            ><el-switch v-model="model.auto_start" aria-label="自动启动呼叫任务"
          /></el-form-item> </el-form
      ></el-tab-pane>
      <el-tab-pane label="呼频规则"
        ><el-form label-position="top"
          ><el-form-item label="同号码每日最多拨打次数"
            ><el-input-number
              v-model="model.max_daily"
              :min="1"
              :max="20"
              aria-label="每日拨打次数" /></el-form-item
          ><el-form-item label="最小拨打间隔（秒）"
            ><el-input-number
              v-model="model.min_interval_seconds"
              :min="0"
              :max="604800"
              aria-label="最小拨打间隔" /></el-form-item></el-form
      ></el-tab-pane>
      <el-tab-pane label="话单推送" lazy><SettingSection section="integration" /></el-tab-pane>
      <el-tab-pane label="欠费推送"
        ><el-form-item label="余额低于此金额时通知（元）"
          ><el-input-number v-model="model.low_balance" :min="0" aria-label="欠费提醒阈值"
        /></el-form-item>
        <p>余额提醒发送到消息中心；未导入有效账单余额时不生成余额判断。</p></el-tab-pane
      >
      <el-tab-pane label="意向达成率"
        ><el-form-item label="目标意向率（%）"
          ><el-input-number
            v-model="model.target_intent_rate"
            :min="0"
            :max="100"
            aria-label="目标意向率" /></el-form-item
      ></el-tab-pane>
      <el-tab-pane label="智能评估"
        ><p>按照已发布话术的意向规则、挂机评级和任务目标意向率评估任务，结果可在历史任务中查看。</p>
        <el-button @click="$router.push('/admin/scripts')">配置话术评估规则</el-button></el-tab-pane
      >
    </el-tabs>
    <div class="toolbar">
      <el-button @click="restore">恢复</el-button
      ><el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </div></el-card
  >
</template>
