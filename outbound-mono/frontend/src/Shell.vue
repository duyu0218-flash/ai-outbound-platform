<script setup>
import {computed,ref,watch,onMounted} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {adminNavigation} from './router'
import {apiRequest} from './api'
import {session,logout,request} from './session'
import {t,language,setLanguage} from './i18n'
const props=defineProps({role:String}),route=useRoute(),router=useRouter(),collapsed=ref(false),healthy=ref(null)
const opsProfile=ref(null);onMounted(()=>request('/api/v1/ops/me').then(v=>opsProfile.value=v).catch(()=>{}))
const navigation=computed(()=>props.role==='admin'?adminNavigation.map(([path,label])=>({path:'/admin'+(path?'/'+path:''),label})):[{path:'/agent',label:'workspace'},...(session.user?.is_supervisor&&opsProfile.value?.all_scope?[{path:'/agent/quality',label:'qualityReview'}]:[]),{path:'/agent/calls',label:'calls'},...['calls','leads','reports','notices','workspace'].map((p,i)=>({path:'/agent/operations/'+p,label:['话单查询','线索与跟进','运营报表','通知中心','坐席个人配置'][i]}))])
watch(()=>route.path,()=>apiRequest('/healthz').then(()=>healthy.value=true).catch(()=>healthy.value=false),{immediate:true})
async function leave(){try{await logout()}finally{router.replace('/'+props.role+'/login')}}
</script>
<template><el-container class="app-shell"><el-aside :width="collapsed?'76px':'232px'" class="sidebar"><a class="brand" :href="'/'+role" @click.prevent="router.push('/'+role)"><b>◉</b><span v-if="!collapsed">{{t('product')}}<small>{{t(role==='admin'?'adminPortal':'agentPortal')}}</small></span></a><el-menu router :default-active="route.path" :collapse="collapsed" background-color="#0b1736" text-color="#c6d2ee" active-text-color="#fff"><el-menu-item v-for="(item,index) in navigation" :key="item.path" :index="item.path"><el-icon><span>{{index+1}}</span></el-icon><template #title>{{t(item.label)}}</template></el-menu-item></el-menu><footer v-if="!collapsed">{{t('tenant')}} #{{session.user?.tenant_id}} · {{session.user?.role}}</footer></el-aside><el-container><el-header class="app-header"><div class="actions"><el-button aria-label="收起导航" @click="collapsed=!collapsed">☰</el-button><strong>{{t(route.meta.label||'dashboard')}}</strong></div><div class="actions"><el-tag :type="healthy===false?'danger':'success'">{{t(healthy===false?'offline':'online')}}</el-tag><el-select :model-value="language" aria-label="语言" class="language" @change="setLanguage"><el-option label="中文" value="zh-CN"/><el-option label="English" value="en-US"/></el-select><span>{{session.user?.full_name||session.user?.username}}</span><el-button @click="leave">{{t('logout')}}</el-button></div></el-header><el-main><router-view :key="route.path+':'+session.epoch"/></el-main></el-container></el-container></template>
