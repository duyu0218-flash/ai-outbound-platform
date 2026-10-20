<script setup>
import {ref,watch} from 'vue'
import {request} from '../session'
import {useData,action} from '../data'
import {t} from '../i18n'
import {settingFields} from '../settings-fields'
const props=defineProps({section:String}),query=useData(()=>'/api/v1/admin/settings/'+props.section),overview=useData(()=>props.section==='capacity'?'/api/v1/admin/system-overview':''),model=ref({}),busy=ref(false)
watch(()=>query.data.value,item=>{if(item)model.value=JSON.parse(JSON.stringify(item.data))})
async function save(){busy.value=true;try{await action(()=>request('/api/v1/admin/settings/'+props.section,'PUT',{data:model.value}),{refresh:async()=>{await query.load();await overview.load()}})}finally{busy.value=false}}
</script><template><el-alert v-if="query.error.value" :title="query.error.value" type="error"/><el-alert v-if="section==='capacity'" type="warning" :title="t('capacityChangeWarning')" :description="t('capacityChangeDescription')"/><el-alert v-if="section==='compliance'" type="info" :title="t('complianceRetentionHint')"/><FieldForm v-if="query.data.value" :model="model" :fields="settingFields[section]" :busy="busy" @submit="save"/><el-descriptions v-if="section==='capacity'&&overview.data.value" :column="1" border><el-descriptions-item v-for="[key,label] in [['effective_max_concurrent_calls','effectiveCapacity'],['line_max_concurrency','lineCapacity'],['active_calls','activeCalls'],['available_slots','availableSlots'],['limiting_source','limitingSource']]" :key="key" :label="t(label)">{{overview.data.value.capacity[key]??t('notLimited')}}</el-descriptions-item></el-descriptions></template>
