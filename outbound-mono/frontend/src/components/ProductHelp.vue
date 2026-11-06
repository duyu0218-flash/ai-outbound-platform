<script setup>
import { ref, watch } from 'vue';
const props = defineProps({ mode: String });
const active = ref(props.mode || 'single');
watch(
  () => props.mode,
  (value) => {
    if (value) active.value = value;
  },
);
const modes = { single: '个性化单呼', group: '个性化群呼', robot: '纯机器人外呼' };
</script>
<template>
  <h3>三种外呼作业模式</h3>
  <el-tabs v-model="active"
    ><el-tab-pane v-for="(label, key) in modes" :key="key" :label="label" :name="key"
  /></el-tabs>
  <ol v-if="active === 'single'">
    <li>在工作台检查麦克风、扬声器与线路注册状态，设置为可接听。</li>
    <li>选择客户与手拨线路，使用个人录音或统一声音发起单次呼叫。</li>
    <li>通话结束后核对录音、填写表单并登记跟进结果。</li>
  </ol>
  <ol v-else-if="active === 'group'">
    <li>维护坐席组并让坐席签入，发布支持人机协同的话术。</li>
    <li>设置坐席组倍速与平均等待时长，绑定接待坐席组。</li>
    <li>选择号码批次、话术和线路，核对频控及过滤策略后确认外呼。</li>
    <li>观察进线、等待、呼损与接管情况；排队增长时降低倍速或暂停。</li>
  </ol>
  <ol v-else>
    <li>发布话术与知识，使用纯 AI 模式创建任务。</li>
    <li>配置采集、评级、挂机短信和接口节点，先调试再确认外呼。</li>
    <li>在任务详情观察进度、有效意向及成本，按需要开启自动止损。</li>
  </ol>
  <el-collapse>
    <el-collapse-item title="1. 使用前设备检测" name="device"
      ><p>
        进入工作台软电话，授权麦克风，选择输入与输出设备。先做麦克风检查并确认 SIP
        已注册，再签入接听。
      </p></el-collapse-item
    >
    <el-collapse-item title="2. 移动端与个性录音" name="recording"
      ><p>
        在话术中选择“个性录音”，生成有期限的录音链接并在手机打开。逐段录制、试听并回传；个人录音与统一录音按已发布配置使用。
      </p></el-collapse-item
    >
    <el-collapse-item title="3. 坐席页面设置" name="seat"
      ><p>
        在个人配置中设置整理时间、自动接管与进线提示音。统一配置的项目由管理员维护，个人设置不覆盖统一要求。
      </p></el-collapse-item
    >
    <el-collapse-item title="4. 如何进行外呼操作" name="dial"
      ><p>
        支持单任务多坐席、群呼及单任务单坐席。快捷创建表单中“只创建不外呼”保存草稿，“确认外呼”在准入检查通过后启动。
      </p></el-collapse-item
    >
    <el-collapse-item title="5. 如何控制进线速度" name="pacing"
      ><p>
        签入坐席数 × 倍速值 =
        坐席组可用最大并发；0关闭倍速。平均等待、可接待人员、人工排队、账号与线路上限同时限制新拨号。
      </p></el-collapse-item
    >
    <el-collapse-item title="6. 坐席指标统计" name="metrics"
      ><p>
        在坐席监控、快捷面板与账号统计查看签入、空闲、接管及整理时间。通话记录提供逐句时间、角色、话术节点与挂机事件，录音操作受账号权限控制。
      </p></el-collapse-item
    >
    <el-collapse-item title="7. 账户余额与充值记录" name="balance"
      ><p>
        充值中心展示已导入的真实账单与额度流水；付款与到账以企业已接入的服务商为准。没有可用支付通道时请联系账户管理员。
      </p></el-collapse-item
    >
    <el-collapse-item title="表单管理简介" name="forms"
      ><p>
        先新建表单，再增加字段并发布版本。分配“可使用账号”控制提交权限，选择质检人员后按轮询生成质检待办。修改字段会创建新版本，历史结果保留原口径。
      </p></el-collapse-item
    >
  </el-collapse>
</template>
