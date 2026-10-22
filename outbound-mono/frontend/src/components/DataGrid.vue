<script setup>
import { computed, ref, watch } from 'vue';
import { t } from '../i18n';
import { formatDate } from '../api';
const props = defineProps({
  rows: { type: Array, default: () => [] },
  columns: { type: Array, default: () => [] },
  loading: Boolean,
  selection: Boolean,
  rowKey: { default: 'id' },
  height: [String, Number],
  pagination: { type: [Number, Boolean], default: false },
});
const emit = defineEmits(['selection', 'sort']);
const current = ref(1),
  pageSize = computed(() => (typeof props.pagination === 'number' ? props.pagination : 10)),
  visibleRows = computed(() =>
    props.pagination
      ? (props.rows || []).slice(
          (current.value - 1) * pageSize.value,
          current.value * pageSize.value,
        )
      : props.rows || [],
  );
watch(
  () => props.rows,
  () => {
    current.value = Math.min(
      current.value,
      Math.max(1, Math.ceil((props.rows?.length || 0) / pageSize.value)),
    );
  },
);
const columns = computed(() =>
  props.columns.map((col) => (typeof col === 'string' ? { key: col, label: col } : col)),
);
function show(row, col) {
  const value = col.key?.split('.').reduce((data, key) => data?.[key], row);
  if (col.render) return col.render(value, row);
  if (col.date) return formatDate(value);
  if (col.translate) return t(String(value));
  if (typeof value === 'boolean') return t(value ? 'yes' : 'no');
  if (Array.isArray(value)) return value.join(', ');
  if (value && typeof value === 'object') return JSON.stringify(value);
  return value ?? '-';
}
</script>
<template>
  <div>
    <el-table
      :data="visibleRows"
      :row-key="rowKey"
      v-loading="loading"
      :max-height="height"
      @selection-change="emit('selection', $event)"
      @sort-change="emit('sort', $event)"
      stripe
      ><el-table-column v-if="selection" type="selection" width="46" /><el-table-column
        v-for="col in columns"
        :key="col.key"
        :prop="col.key"
        :label="t(col.label)"
        :min-width="col.width || 120"
        :show-overflow-tooltip="!col.wrap"
        :sortable="col.sortable || false"
        ><template #default="{ row }"
          ><slot :name="col.key" :row="row" :value="row[col.key]"
            ><el-tag
              v-if="col.tag"
              :type="
                ['failed', 'revoked', 'rejected'].includes(row[col.key]) ? 'danger' : undefined
              "
              >{{ show(row, col) }}</el-tag
            ><span v-else>{{ show(row, col) }}</span></slot
          ></template
        ></el-table-column
      ><el-table-column v-if="$slots.actions" :label="t('actions')" :min-width="240" fixed="right"
        ><template #default="{ row }"
          ><div class="actions"><slot name="actions" :row="row" /></div></template></el-table-column
      ><template #empty><el-empty :description="t('empty')" /></template></el-table
    ><el-pagination
      v-if="pagination && (rows?.length || 0) > pageSize"
      v-model:current-page="current"
      :page-size="pageSize"
      :total="rows?.length || 0"
      layout="prev,pager,next"
    />
  </div>
</template>
