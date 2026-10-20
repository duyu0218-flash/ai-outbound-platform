<script setup>
import {ref,onMounted} from 'vue'
import {ops,perform} from '../operations'
const conf=ref(null),error=ref('')
async function load(){try{conf.value=await ops('/workspace')}catch(e){error.value=e.message}}
onMounted(load)
</script><template><PageHeader title="坐席个人配置"/><el-alert v-if="error" :title="error" type="error"/><el-card v-if="conf"><p>当前整理剩余 {{conf.remaining_seconds}} 秒。{{conf.personal_allowed?'允许个人配置':'使用团队统一配置'}}</p><el-form label-position="top"><el-form-item label="通话后整理秒数"><el-input-number v-model="conf.after_call_seconds" :disabled="!conf.personal_allowed" :min="0" :max="600" aria-label="个人整理秒数"/></el-form-item><el-form-item label="人工接收等待秒数"><el-input-number v-model="conf.queue_timeout_seconds" :disabled="!conf.personal_allowed" :min="5" :max="600" aria-label="个人转接等待秒数"/></el-form-item><el-button :disabled="!conf.personal_allowed" @click="perform(()=>ops('/workspace','POST',{...conf,expected_revision:conf.personal_revision}),load)">保存个人配置</el-button></el-form></el-card></template>
