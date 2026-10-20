<script setup>defineProps({value:null})</script><template><pre class="json-view">{{typeof value==='string'?value:JSON.stringify(value,null,2)}}</pre></template>
