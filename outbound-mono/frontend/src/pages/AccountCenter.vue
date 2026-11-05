<script setup>
import AccountResources from '../components/AccountResources.vue';
import Users from './Users.vue';
import OperationsTeams from './OperationsTeams.vue';
import SeatMonitor from '../components/SeatMonitor.vue';
import System from './System.vue';
</script>
<template>
  <PageHeader title="用户管理" /><el-tabs
    ><el-tab-pane label="账户管理"
      ><Users /><el-divider>账号配额与有效期</el-divider><AccountResources /></el-tab-pane
    ><el-tab-pane label="分组管理" lazy><OperationsTeams /></el-tab-pane
    ><el-tab-pane label="坐席设置" lazy><SeatMonitor /></el-tab-pane
    ><el-tab-pane label="账户记录" lazy><System /></el-tab-pane
  ></el-tabs>
</template>
