<script setup>
import { t } from '../i18n';
import SettingSection from '../components/SettingSection.vue';
</script>
<template>
  <PageHeader title="settings" description="settingsHint"
    ><el-button @click="$router.push('/admin/voice-ai')"
      >供应商与技术路线对比</el-button
    ></PageHeader
  ><el-card
    ><el-tabs
      ><el-tab-pane
        v-for="section in ['capacity', 'ai', 'sms', 'compliance', 'integration']"
        :key="section"
        :label="
          t(
            section === 'ai'
              ? 'aiVoice'
              : section === 'sms'
                ? 'smsSettings'
                : section === 'capacity'
                  ? 'capacitySettings'
                  : section === 'integration'
                    ? 'integrations'
                    : 'compliance',
          )
        "
        lazy
        ><SettingSection :section="section" /></el-tab-pane></el-tabs
  ></el-card>
</template>
