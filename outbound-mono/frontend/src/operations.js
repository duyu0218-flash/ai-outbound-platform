import {ref} from 'vue'
import {request} from './session'
import {ElMessage} from 'element-plus'
export const ops=(path,method='GET',body)=>request('/api/v1/ops'+path,method,body)
export function useOperations(){const data=ref(null),error=ref(''),busy=ref(false);async function run(work){busy.value=true;error.value='';try{data.value=await work();return data.value}catch(e){error.value=e.message;throw e}finally{busy.value=false}}return {data,error,busy,run}}
export async function perform(work,after){try{const result=await work();ElMessage.success('已保存');if(after)await after();return result}catch(e){ElMessage.error(e.message);return null}}
export const parse=(raw,fallback={})=>{try{return typeof raw==='string'?JSON.parse(raw):raw??fallback}catch{return fallback}}
export function query(values){return new URLSearchParams(Object.entries(values).filter(([,v])=>v!==''&&v!=null).map(([k,v])=>[k,String(v)])).toString()}
export const prefix=()=>location.pathname.startsWith('/agent')?'/agent':'/admin'
export const stageOptions=[['open','待跟进'],['in_progress','跟进中'],['completed','已完成'],['invalid','无效']]
