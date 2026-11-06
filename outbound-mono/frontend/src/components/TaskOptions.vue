<script setup>
import { useData } from '../data';
defineProps({ maskLocked: Boolean });
const model = defineModel({ required: true });
const catalog = useData('/api/v1/workspace/task-resources/strategies');
</script>
<template>
  <el-divider>策略与过滤</el-divider
  ><el-form label-position="top" class="form-grid">
    <el-form-item label="呼频规则"
      ><el-select v-model="model.frequency_rule_id" clearable aria-label="任务呼频规则"
        ><el-option label="使用默认规则" value="" /><el-option
          v-for="r in catalog.data.value?.frequency || []"
          :key="r.object_id"
          :value="r.object_id"
          :label="r.name" /></el-select
    ></el-form-item>
    <el-form-item label="智能外呼规则"
      ><el-select v-model="model.evaluation_rule_id" clearable aria-label="智能外呼规则"
        ><el-option label="不使用评估规则" value="" /><el-option
          v-for="r in catalog.data.value?.evaluation || []"
          :key="r.object_id"
          :value="r.object_id"
          :label="r.name" /></el-select
    ></el-form-item>
    <el-form-item label="过滤策略"
      ><el-select v-model="model.filter_group_ids" multiple aria-label="任务过滤策略"
        ><el-option
          v-for="r in catalog.data.value?.filters || []"
          :key="r.object_id"
          :value="r.object_id"
          :label="r.name" /></el-select
      ><small>默认黑名单与平台生效策略始终参与拨号校验。</small></el-form-item
    >
    <el-form-item label="任务所属"
      ><el-select v-model="model.owner_id" clearable aria-label="任务所属"
        ><el-option
          v-for="u in catalog.data.value?.users || []"
          :key="u.id"
          :value="u.id"
          :label="u.full_name || u.username" /></el-select
    ></el-form-item>
    <el-form-item label="呼叫顺序"
      ><el-radio-group v-model="model.call_order" aria-label="呼叫顺序"
        ><el-radio value="sequential">顺序</el-radio
        ><el-radio value="random">随机</el-radio></el-radio-group
      ></el-form-item
    >
    <el-form-item label="加企微"
      ><el-switch v-model="model.auto_wecom" aria-label="任务随机加企微" /><small
        >从已配置的企业微信号中随机选择，经启用的服务商提交添加请求。</small
      ></el-form-item
    >
    <el-form-item label="成功单拦截"
      ><el-switch v-model="model.block_successful" aria-label="任务成功单拦截"
    /></el-form-item>
  </el-form>
  <el-form-item label="号码加密展示"
    ><el-switch
      v-model="model.mask_numbers"
      :disabled="maskLocked"
      aria-label="任务号码加密展示"
    /><small>任务保存后只能开启隐藏，无法恢复展示完整号码。</small></el-form-item
  >
  <h3>工作时间段</h3>
  <div v-for="(w, i) in model.windows" :key="i" class="toolbar">
    <el-input v-model="w.start" :aria-label="'任务工作开始' + i" placeholder="09:00" /><span
      >至</span
    ><el-input v-model="w.end" :aria-label="'任务工作结束' + i" placeholder="20:00" /><el-button
      @click="model.windows.splice(i, 1)"
      >移除时段</el-button
    >
  </div>
  <el-button
    :disabled="model.windows.length >= 12"
    @click="model.windows.push({ start: '09:00', end: '20:00' })"
    >添加工作时间段</el-button
  >
  <el-alert v-if="catalog.error.value" type="error" :title="catalog.error.value" />
</template>
