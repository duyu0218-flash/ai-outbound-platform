<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { language, t } from '../i18n';

const props = defineProps({ pages: Array, activePath: String });
const emit = defineEmits(['select', 'close', 'refresh']);
const lane = ref(null);
const more = ref(null);
const visiblePaths = ref(null);
const hiddenPages = computed(() =>
  props.pages.filter((page) => visiblePaths.value && !visiblePaths.value.has(page.path)),
);
let observer;

function fitPages() {
  if (!lane.value) return;
  const tabs = [...lane.value.querySelectorAll('.workspace-page')];
  const gap = parseFloat(getComputedStyle(lane.value).columnGap);
  const width = lane.value.clientWidth;
  const needed = tabs.reduce((total, tab) => total + tab.offsetWidth, 0) + gap * (tabs.length - 1);
  if (needed <= width) {
    visiblePaths.value = null;
    return;
  }
  const current = tabs.find((tab) => tab.dataset.path === props.activePath);
  const visible = new Set([props.activePath]);
  let remaining = width - more.value.offsetWidth - (current?.offsetWidth || 0) - gap;
  for (const tab of tabs) {
    const path = tab.dataset.path;
    if (path === props.activePath) continue;
    const cost = tab.offsetWidth + gap;
    if (cost > remaining) break;
    visible.add(path);
    remaining -= cost;
  }
  visiblePaths.value = visible;
}

watch(() => [props.pages.length, props.activePath, language.value], fitPages, { flush: 'post' });
onMounted(() => {
  fitPages();
  observer = new ResizeObserver(fitPages);
  observer.observe(lane.value);
  document.fonts.ready.then(fitPages);
});
onUnmounted(() => observer?.disconnect());
</script>

<template>
  <nav class="workspace-pages" :aria-label="t('openPages')">
    <div ref="lane" class="workspace-lane">
      <span
        v-for="item in pages"
        :key="item.path"
        :data-path="item.path"
        class="workspace-page"
        :class="{
          'is-current': item.path === activePath,
          'is-overflow': visiblePaths && !visiblePaths.has(item.path),
        }"
      >
        <button
          type="button"
          class="workspace-page-label"
          :title="t(item.label)"
          :aria-current="item.path === activePath ? 'page' : undefined"
          @click="emit('select', item)"
        >
          {{ t(item.label) }}
        </button>
        <button
          v-if="pages.length > 1"
          type="button"
          class="workspace-close"
          :aria-label="t('closePage', { page: t(item.label) })"
          :title="t('closePage', { page: t(item.label) })"
          @click="emit('close', item)"
        >
          ×
        </button>
      </span>
      <div ref="more" class="workspace-more" :class="{ 'is-overflow': !hiddenPages.length }">
        <el-dropdown trigger="click" @command="(item) => emit('select', item)">
          <button
            type="button"
            class="workspace-tool"
            :aria-label="t('morePages', { count: hiddenPages.length })"
          >
            {{ t('more') }} {{ hiddenPages.length }}
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="item in hiddenPages" :key="item.path" :command="item">
                {{ t(item.label) }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
    <button
      type="button"
      class="workspace-tool"
      :aria-label="t('refreshPage')"
      @click="emit('refresh')"
    >
      {{ t('refresh') }}
    </button>
  </nav>
</template>

<style scoped>
.workspace-pages,
.workspace-lane,
.workspace-page {
  display: flex;
  align-items: center;
  min-width: 0;
  white-space: nowrap;
}
.workspace-pages,
.workspace-lane {
  gap: 6px;
}
.workspace-pages {
  margin-bottom: 12px;
}
.workspace-lane {
  position: relative;
  overflow: hidden;
  container-type: inline-size;
  flex: 1;
}
.workspace-page {
  flex: 0 0 auto;
  max-width: min(176px, 100cqw);
  height: 34px;
  border: 1px solid #e0e6f0;
  border-radius: 6px;
  background: white;
  color: #40506c;
}
.workspace-page:has(.workspace-close) {
  max-width: min(176px, calc(100cqw - 86px));
}
.workspace-page.is-current {
  border-color: #a9bff7;
  background: #eef3ff;
  color: #214fbe;
}
.is-overflow {
  position: absolute;
  inset-inline-start: 0;
  visibility: hidden;
}
.workspace-page-label,
.workspace-close,
.workspace-tool {
  height: 32px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.workspace-page-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  padding: 0 9px;
}
.is-current .workspace-page-label {
  font-weight: 600;
}
.workspace-close {
  padding: 0 7px;
  font-size: 17px;
  flex: 0 0 auto;
}
.workspace-tool {
  flex: 0 0 auto;
  padding: 0 8px;
  color: #40506c;
}
.workspace-more .workspace-tool {
  width: 80px;
}
.workspace-page-label:hover,
.workspace-close:hover,
.workspace-tool:hover {
  background: #e7eefc;
}
.workspace-page-label:focus-visible,
.workspace-close:focus-visible,
.workspace-tool:focus-visible {
  outline-offset: -2px;
}
</style>
