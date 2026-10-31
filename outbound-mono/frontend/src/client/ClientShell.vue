<script setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { clientSession, clientLogout } from './session';
const route = useRoute(),
  router = useRouter();
const user = computed(() => clientSession.user),
  menu = computed(() => [
    { path: '/client', label: '线索' },
    { path: '/client/stats', label: '数据看板' },
    ...(user.value?.abilities?.manage_members
      ? [{ path: '/client/members', label: '成员管理' }]
      : []),
    { path: '/client/password', label: '修改密码' },
  ]);
async function leave() {
  try {
    await clientLogout();
  } finally {
    router.replace('/client/login');
  }
}
</script>
<template>
  <el-container class="app-shell client-shell"
    ><el-aside width="200px" class="sidebar"
      ><div class="brand">
        <b>◉</b
        ><span
          >企业线索门户<small>{{ user?.client?.name }}</small></span
        >
      </div>
      <el-menu
        router
        :default-active="route.path"
        background-color="#0b1736"
        text-color="#c6d2ee"
        active-text-color="#fff"
        ><el-menu-item
          v-for="item in menu"
          :key="item.path"
          :index="item.path"
          :disabled="user?.must_change_password && item.path !== '/client/password'"
          >{{ item.label }}</el-menu-item
        ></el-menu
      ></el-aside
    ><el-container
      ><el-header class="app-header"
        ><strong>{{ route.meta.label }}</strong>
        <div class="actions">
          <span>{{ user?.full_name }} · {{ user?.role === 'owner' ? '企业管理员' : '成员' }}</span
          ><el-button @click="leave">退出</el-button>
        </div></el-header
      ><el-main
        ><router-view :key="route.path + ':' + clientSession.epoch" /></el-main></el-container
  ></el-container>
</template>
