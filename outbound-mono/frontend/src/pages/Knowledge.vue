<script setup>
import ResourcePage from '../components/ResourcePage.vue'
const fields=[{key:'title',label:'name',required:true},{key:'category',label:'category'},{key:'tags',label:'tags'},{key:'content',label:'content',required:true,type:'textarea',rows:10},{key:'is_active',label:'enabled',type:'boolean'}]
</script><template><ResourcePage title="knowledge" description="knowledgeHint" base="/api/v1/knowledge" path="/api/v1/knowledge" :fields="fields" :defaults="{category:'default',tags:'',is_active:true}" deletable :columns="[{key:'title',label:'name'},{key:'category',label:'category'},{key:'content',label:'content'},{key:'version',label:'version'},{key:'is_active',label:'status'}]"/></template>
