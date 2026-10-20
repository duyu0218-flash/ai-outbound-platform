<script setup>
import {computed} from 'vue'
import zh from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import {language} from './i18n'
const locale=computed(()=>language.value.startsWith('en')?en:zh)
</script><template><el-config-provider :locale="locale"><router-view/></el-config-provider></template>
