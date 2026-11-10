<script setup>
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { session } from '../session';
import { t } from '../i18n';

const props = defineProps({
  role: String,
  groups: Array,
  groupOpen: Object,
  collapsed: Boolean,
  searchable: Boolean,
});
const emit = defineEmits(['toggle-group', 'select-current']);
const route = useRoute();
const menu = ref(null);
const search = ref('');
const keyword = computed(() => search.value.trim().toLocaleLowerCase());
const visibleGroups = computed(() => {
  if (!keyword.value) return props.groups;
  const matches = (label) => t(label).toLocaleLowerCase().includes(keyword.value);
  const filterItems = (items) => items.filter((item) => matches(item.label));
  return props.groups.flatMap((group) => {
    if (matches(group.group)) return [group];
    if (!group.sections) {
      const items = filterItems(group.items);
      return items.length ? [{ ...group, items }] : [];
    }
    const sections = group.sections
      .map((section) => ({
        ...section,
        items: matches(section.group) ? section.items : filterItems(section.items),
      }))
      .filter((section) => section.items.length);
    return sections.length ? [{ ...group, sections }] : [];
  });
});
function onSelect(path) {
  if (path === route.path) emit('select-current');
}
function activateWithKeyboard(event) {
  if (props.searchable && (event.key === 'Enter' || event.key === ' ')) {
    event.preventDefault();
    event.currentTarget.click();
  }
}
function scrollToCurrent() {
  menu.value?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: 'nearest' });
}
defineExpose({ scrollToCurrent });
</script>

<template>
  <nav
    class="side-navigation"
    :class="{ 'is-drawer': searchable }"
    :aria-label="t('mainNavigation')"
  >
    <router-link v-if="!searchable" class="brand" :to="'/' + role" :aria-label="t('product')">
      <b aria-hidden="true">◉</b>
      <span v-if="!collapsed">
        {{ t('product') }}<small>{{ t(role === 'admin' ? 'adminPortal' : 'agentPortal') }}</small>
      </span>
    </router-link>
    <el-input
      v-if="searchable"
      v-model="search"
      class="navigation-search"
      :aria-label="t('searchNavigation')"
      :placeholder="t('searchNavigation')"
      clearable
    />
    <div ref="menu" class="navigation-list">
      <p v-if="!visibleGroups.length" class="navigation-empty" role="status">
        {{ t('navigationEmpty') }}
      </p>
      <el-menu
        v-else
        router
        :default-active="route.path"
        :collapse="collapsed"
        background-color="#0b1736"
        text-color="#c6d2ee"
        active-text-color="#fff"
        @select="onSelect"
      >
        <el-menu-item-group v-for="section in visibleGroups" :key="section.group">
          <template #title>
            <span v-if="collapsed" class="nav-group-rule"></span>
            <span v-else-if="keyword" class="nav-group">{{ t(section.group) }}</span>
            <button
              v-else
              class="nav-group nav-toggle"
              :aria-label="'展开或收起' + t(section.group)"
              :aria-expanded="groupOpen[section.group] !== false"
              @click="emit('toggle-group', section.group)"
            >
              {{ t(section.group) }}
            </button>
          </template>
          <template
            v-for="child in section.sections || [{ items: section.items }]"
            :key="child.group || section.group"
          >
            <div
              v-if="child.group && !collapsed"
              v-show="keyword || groupOpen[section.group] !== false"
              class="nav-subgroup-title"
            >
              <span v-if="keyword" class="nav-subgroup">{{ t(child.group) }}</span>
              <button
                v-else
                class="nav-subgroup nav-toggle"
                :aria-label="'展开或收起' + t(child.group)"
                :aria-expanded="groupOpen[section.group + ':' + child.group] !== false"
                @click="emit('toggle-group', section.group + ':' + child.group)"
              >
                {{ t(child.group) }}
              </button>
            </div>
            <el-menu-item
              v-for="item in child.items"
              :key="item.path"
              :index="item.path"
              :aria-current="item.path === route.path ? 'page' : undefined"
              :tabindex="searchable ? 0 : -1"
              @keydown="activateWithKeyboard"
              v-show="
                collapsed ||
                keyword ||
                (groupOpen[section.group] !== false &&
                  (!child.group || groupOpen[section.group + ':' + child.group] !== false))
              "
            >
              <el-icon><span class="nav-dot"></span></el-icon>
              <template #title>{{ t(item.label) }}</template>
            </el-menu-item>
          </template>
        </el-menu-item-group>
      </el-menu>
    </div>
    <footer v-if="!searchable && !collapsed">
      {{ t('tenant') }} #{{ session.user?.tenant_id }} · {{ session.user?.role }}
    </footer>
  </nav>
</template>

<style scoped>
.nav-toggle {
  display: block;
  border: 0;
  background: transparent;
  text-align: left;
  width: 100%;
  cursor: pointer;
  padding: 0;
}
.nav-toggle:after {
  content: '⌄';
  float: right;
}
.nav-toggle[aria-expanded='false']:after {
  content: '›';
}
:global(.navigation-drawer) {
  --el-drawer-bg-color: #0b1736;
}
:global(.navigation-drawer .el-drawer__header) {
  margin: 0;
  padding: 16px;
  color: #fff;
}
:global(.navigation-drawer .el-drawer__close-btn) {
  color: #c6d2ee;
}
:global(.navigation-drawer .el-drawer__body) {
  padding: 0;
  overflow: hidden;
}
.is-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
  color: #c6d2ee;
}
.navigation-search {
  width: auto;
  margin: 0 16px 12px;
}
.is-drawer .navigation-list {
  min-height: 0;
  overflow: auto;
  overscroll-behavior: contain;
  padding-bottom: env(safe-area-inset-bottom);
}
.is-drawer :deep(.el-menu-item) {
  min-height: 44px;
  height: auto;
  line-height: 1.4;
  padding-block: 10px;
  white-space: normal;
}
.is-drawer :deep(.el-menu-item:focus-visible) {
  outline: 2px solid #92b5ff;
  outline-offset: -2px;
}
.navigation-empty {
  margin: 16px;
  font-size: 14px;
}
</style>
