<script setup>
import {ref,computed} from 'vue'
import {request} from '../session'
import {useData,action} from '../data'
import {t} from '../i18n'
const page=ref(1),keyword=ref(''),search=ref(''),open=ref(false),editing=ref(null),model=ref({}),busy=ref(false)
const query=useData(()=>`/api/v1/contacts?page=${page.value}&size=50${search.value?'&keyword='+encodeURIComponent(search.value):''}`)
const columns=[{key:'id',label:'ID',width:70},{key:'phone',label:'phone'},{key:'name',label:'contactName'},{key:'tags',label:'tags'},{key:'consent_state',label:'consent',translate:true,tag:true},{key:'dnc',label:'dnc'},{key:'created_at',label:'createdAt',date:true}]
const fields=computed(()=>[{key:'phone',label:'phone',required:!editing.value,disabled:!!editing.value},{key:'name',label:'contactName'},{key:'tags',label:'tags',wide:true},{key:'consent_state',label:'consent',options:[{value:'consented',label:'consented'},{value:'not_consented',label:'notConsented'},{value:'revoked',label:'revoked'},{value:'unknown',label:'unknown'}]},{key:'timezone',label:'timezone'},{key:'dnc',label:'dnc',type:'boolean'}])
function edit(row){editing.value=row;model.value=row?{phone:row.phone,name:row.name,tags:row.tags,consent_state:row.consent_state,timezone:row.timezone,dnc:row.dnc}:{consent_state:'unknown',dnc:false,timezone:'Asia/Shanghai'};open.value=true}
async function save(){busy.value=true;try{await action(()=>request('/api/v1/contacts'+(editing.value?'/'+editing.value.id:''),editing.value?'PATCH':'POST',model.value),{refresh:query.load});open.value=false}finally{busy.value=false}}
function find(){search.value=keyword.value;page.value=1;query.load()}
function reset(){keyword.value='';search.value='';page.value=1;query.load()}
</script>
<template><PageHeader title="contacts" description="contactHint"><el-button type="primary" @click="edit()">{{t('addContact')}}</el-button></PageHeader><el-card><div class="toolbar"><el-input v-model="keyword" :placeholder="t('phone')+' / '+t('name')" aria-label="搜索联系人" clearable @keyup.enter="find"/><el-button type="primary" @click="find">{{t('search')}}</el-button><el-button @click="reset">{{t('reset')}}</el-button><el-button @click="query.load">{{t('refresh')}}</el-button></div><el-alert v-if="query.error.value" type="error" :title="query.error.value"/><DataGrid :rows="query.data.value" :columns="columns" :loading="query.loading.value"><template #actions="{row}"><el-button link @click="edit(row)">{{t('edit')}}</el-button><el-button link type="danger" @click="action(()=>request('/api/v1/contacts/'+row.id,'DELETE'),{confirm:t('confirmDelete'),refresh:query.load})">{{t('delete')}}</el-button></template></DataGrid><Pager :page="page" :count="query.data.value?.length||0" :loading="query.loading.value" @change="page=$event"/></el-card><el-dialog v-model="open" :title="t(editing?'edit':'addContact')" destroy-on-close width="600px"><FieldForm :model="model" :fields="fields" :busy="busy" @submit="save"/></el-dialog></template>
