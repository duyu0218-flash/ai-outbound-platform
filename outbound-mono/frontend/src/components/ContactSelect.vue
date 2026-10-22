<script setup>
import { ref, onMounted, watch } from 'vue';
import { request } from '../session';
import { t } from '../i18n';
const props = defineProps({ modelValue: { type: Array, default: () => [] } }),
  emit = defineEmits(['update:modelValue']),
  options = ref([]),
  page = ref(1),
  keyword = ref(''),
  busy = ref(false),
  more = ref(true),
  serial = ref(0);
async function load(reset = false) {
  if (reset) {
    page.value = 1;
    options.value = [];
  }
  const id = ++serial.value;
  busy.value = true;
  try {
    const items = await request(
      `/api/v1/contacts?page=${page.value}&size=50&keyword=${encodeURIComponent(keyword.value)}`,
    );
    if (id !== serial.value) return;
    const cache = new Map(options.value.map((v) => [v.id, v]));
    for (const row of items) cache.set(row.id, row);
    options.value = [...cache.values()];
    more.value = items.length === 50;
    page.value++;
  } finally {
    if (id === serial.value) busy.value = false;
  }
}
onMounted(() => load());
</script>
<template>
  <el-select
    :model-value="modelValue"
    multiple
    filterable
    remote
    :remote-method="
      (value) => {
        keyword = value;
        load(true);
      }
    "
    :loading="busy"
    @update:model-value="emit('update:modelValue', $event)"
    ><el-option
      v-for="item in options"
      :key="item.id"
      :value="item.id"
      :label="`${item.name || item.phone} · ${item.phone}`"
    /><el-option
      v-for="id in modelValue.filter((id) => !options.some((item) => item.id === id))"
      :key="'saved-' + id"
      :value="id"
      :label="'#' + id"
    /><template #footer
      ><el-button v-if="more" link :loading="busy" @click="load()">{{
        t('loadMoreContacts')
      }}</el-button
      ><span>{{ t('selectedContactsCount', { count: modelValue.length }) }}</span></template
    ></el-select
  >
</template>
