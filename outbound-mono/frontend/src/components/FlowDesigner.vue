<script setup>
import { ref, computed, watch, onUnmounted } from 'vue';
import { request } from '../session';
import { useData, action } from '../data';
import { ElMessage } from 'element-plus';
const props = defineProps({ template: Object }),
  emit = defineEmits(['close']),
  versions = useData(() => `/api/v1/script-templates/${props.template.id}/flows`),
  selectedId = ref(),
  selectedNodeId = ref(),
  graph = ref({ nodes: [], edges: [] }),
  edge = ref({
    source: '',
    target: '',
    condition: 'always',
    keywords: '',
    variable: '',
    value: '',
  });
const selected = computed(() => versions.data.value?.find((v) => v.id === selectedId.value)),
  selectedNode = computed(() => graph.value.nodes.find((n) => n.id === selectedNodeId.value)),
  draft = computed(() => selected.value?.status === 'draft'),
  nodeTypes = {
    message: '播报话术',
    listen: '等待回答',
    collect: '采集信息',
    set: '设置变量',
    branch: '条件分支',
    jump: '跳转节点',
    handoff: '转人工',
    hangup: '结束通话',
  };
function select(id) {
  selectedId.value = id;
  const item = versions.data.value?.find((v) => v.id === id);
  if (item) graph.value = JSON.parse(JSON.stringify(item.graph));
  selectedNodeId.value = null;
}
watch(
  () => versions.data.value,
  (items) => {
    if (items?.length)
      select(items.some((v) => v.id === selectedId.value) ? selectedId.value : items[0].id);
  },
);
async function create() {
  const item = await action(() =>
    request(`/api/v1/script-templates/${props.template.id}/flows`, 'POST', {
      clone_version_id: selected.value?.id,
    }),
  );
  await versions.load();
  if (item) select(item.id);
}
async function save() {
  await action(
    () =>
      request(`/api/v1/script-templates/${props.template.id}/flows/${selected.value.id}`, 'PUT', {
        name: selected.value.name,
        description: selected.value.description,
        graph: graph.value,
      }),
    { refresh: versions.load },
  );
}
async function publish() {
  await action(
    () =>
      request(
        `/api/v1/script-templates/${props.template.id}/flows/${selected.value.id}/publish`,
        'POST',
      ),
    { confirm: '发布后不可直接修改，确认发布？', refresh: versions.load },
  );
}
function addNode(type) {
  const id = type + '-' + Date.now();
  graph.value.nodes.push({
    id,
    type,
    label: nodeTypes[type],
    prompt: '',
    position: {
      x: 260 + graph.value.nodes.length * 34,
      y: 90 + (graph.value.nodes.length % 4) * 110,
    },
  });
  selectedNodeId.value = id;
}
function copyNode() {
  if (!draft.value || !selectedNode.value || selectedNode.value.type === 'start') return;
  const copy = JSON.parse(JSON.stringify(selectedNode.value));
  copy.id = copy.type + '-' + Date.now();
  copy.label = copy.label + ' 副本';
  copy.position = { x: copy.position.x + 40, y: copy.position.y + 40 };
  graph.value.nodes.push(copy);
  selectedNodeId.value = copy.id;
}
function removeNode() {
  graph.value.nodes = graph.value.nodes.filter((n) => n.id !== selectedNodeId.value);
  graph.value.edges = graph.value.edges.filter(
    (e) => e.source !== selectedNodeId.value && e.target !== selectedNodeId.value,
  );
  selectedNodeId.value = null;
}
let move, up;
function clearDrag() {
  if (move) document.removeEventListener('mousemove', move);
  if (up) document.removeEventListener('mouseup', up);
}
function drag(event, node) {
  selectedNodeId.value = node.id;
  if (!draft.value) return;
  event.preventDefault();
  clearDrag();
  const x = event.clientX,
    y = event.clientY,
    nx = node.position.x,
    ny = node.position.y;
  move = (e) => {
    node.position = { x: Math.max(0, nx + e.clientX - x), y: Math.max(0, ny + e.clientY - y) };
  };
  up = clearDrag;
  document.addEventListener('mousemove', move);
  document.addEventListener('mouseup', up);
}
onUnmounted(clearDrag);
const branchPresets = {
  助手: ['intent', 'machine_candidate'],
  全部: ['always', ''],
  未匹配: ['always', ''],
  肯定: ['intent', 'affirm'],
  否定: ['intent', 'deny,rejected'],
  在忙: ['keyword', '在忙,开会,开车,没空'],
};
function setPreset(label) {
  const [condition, keywords] = branchPresets[label];
  Object.assign(edge.value, { label, condition, keywords });
}
function setStart() {
  const start = graph.value.nodes.find((n) => n.type === 'start');
  graph.value.edges = graph.value.edges.filter((e) => e.source !== start.id);
  graph.value.edges.push({
    id: 'edge-' + crypto.randomUUID(),
    source: start.id,
    target: selectedNodeId.value,
    condition: 'always',
    keywords: [],
  });
}
function addEdge() {
  if (!edge.value.source || !edge.value.target) return;
  if (edge.value.source === edge.value.target) return ElMessage.error('起点和终点不能相同');
  graph.value.edges.push({
    ...edge.value,
    id: 'edge-' + Date.now(),
    keywords: edge.value.keywords
      .split(/[,，]/)
      .map((v) => v.trim())
      .filter(Boolean),
  });
  edge.value = {
    source: '',
    target: '',
    condition: 'always',
    keywords: '',
    variable: '',
    value: '',
  };
}
function node(id) {
  return graph.value.nodes.find((n) => n.id === id);
}
const edgeFields = computed(() => [
  { key: 'label', label: '分支名称' },
  {
    key: 'condition',
    label: '条件',
    options: ['always', 'keyword', 'silence', 'intent', 'equals', 'not_equals'],
  },
  { key: 'keywords', label: '关键词（逗号分隔）' },
  { key: 'variable', label: '条件变量' },
  { key: 'value', label: '比较值' },
]);
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="template.name + ' · 话术画布'"
    width="96vw"
    @close="emit('close')"
    destroy-on-close
    ><div class="toolbar">
      <el-select :model-value="selectedId" placeholder="选择版本" @change="select"
        ><el-option
          v-for="item in versions.data.value || []"
          :key="item.id"
          :value="item.id"
          :label="`v${item.version} · ${item.status === 'draft' ? '草稿' : '已发布'}`" /></el-select
      ><el-button @click="create">{{ selected ? '复制为新草稿' : '创建画布' }}</el-button
      ><el-input
        v-if="selected"
        v-model="selected.name"
        aria-label="流程名称"
        :disabled="!draft"
        style="max-width: 200px"
      /><el-button type="primary" :disabled="!draft" @click="save">保存</el-button
      ><el-button :disabled="!draft" @click="publish">发布版本</el-button>
    </div>
    <el-alert v-if="versions.error.value" :title="versions.error.value" type="error" /><el-empty
      v-if="!selected"
      description="先创建一个画布版本"
    />
    <div v-else class="flow-workspace">
      <div class="flow-palette">
        <strong>节点</strong
        ><el-button
          v-for="(label, type) in nodeTypes"
          :key="type"
          :disabled="!draft"
          @click="addNode(type)"
          >{{ label }}</el-button
        >
      </div>
      <div class="flow-canvas">
        <svg class="flow-lines">
          <g
            v-for="line in graph.edges.filter((e) => node(e.source) && node(e.target))"
            :key="line.id"
          >
            <line
              :x1="node(line.source).position.x + 76"
              :y1="node(line.source).position.y + 30"
              :x2="node(line.target).position.x + 76"
              :y2="node(line.target).position.y + 30"
            />
            <text
              :x="(node(line.source).position.x + node(line.target).position.x) / 2 + 76"
              :y="(node(line.source).position.y + node(line.target).position.y) / 2 + 22"
            >
              {{
                line.label ||
                (line.condition === 'keyword' ? line.keywords.join('/') : line.condition)
              }}
            </text>
          </g></svg
        ><button
          v-for="item in graph.nodes"
          :key="item.id"
          type="button"
          class="flow-node"
          :class="{ selected: selectedNodeId === item.id }"
          :style="{ left: item.position.x + 'px', top: item.position.y + 'px' }"
          @mousedown="drag($event, item)"
        >
          <strong>{{ item.label }}</strong
          ><span>{{ item.type }}</span>
        </button>
      </div>
      <aside class="flow-inspector">
        <template v-if="selectedNode"
          ><h4>节点配置</h4>
          <el-input
            v-model="selectedNode.label"
            :disabled="!draft"
            aria-label="节点名称"
          /><el-input
            v-model="selectedNode.prompt"
            type="textarea"
            :rows="5"
            :disabled="!draft"
            aria-label="播报内容"
          /><el-input
            v-if="['collect', 'set'].includes(selectedNode.type)"
            v-model="selectedNode.variable"
            :disabled="!draft"
            aria-label="变量名称"
          /><el-input
            v-if="selectedNode.type === 'set'"
            v-model="selectedNode.value"
            :disabled="!draft"
            aria-label="变量值"
          /><el-button v-if="selectedNode.type !== 'start'" :disabled="!draft" @click="copyNode"
            >复制节点</el-button
          ><el-button
            v-if="selectedNode.type !== 'start'"
            type="danger"
            :disabled="!draft"
            @click="removeNode"
            >删除节点</el-button
          ></template
        >
        <p v-else>点击节点进行配置</p>
        <el-button v-if="draft && selectedNode && selectedNode.type !== 'start'" @click="setStart"
          >设为开始</el-button
        >
        <h4>添加连线</h4>
        <p>选择分支预设可填入匹配条件。每个节点最多一条无条件出口，用于无其他匹配时继续。</p>
        <el-button
          v-for="(_, label) in branchPresets"
          :key="label"
          :disabled="!draft"
          @click="setPreset(label)"
          >{{ label }}</el-button
        >
        <label class="node-link-select"
          >起点<select v-model="edge.source" :disabled="!draft" aria-label="起点">
            <option value="">选择起点</option>
            <option v-for="n in graph.nodes" :key="n.id" :value="n.id">{{ n.label }}</option>
          </select></label
        >
        <label class="node-link-select"
          >终点<select v-model="edge.target" :disabled="!draft" aria-label="终点">
            <option value="">选择终点</option>
            <option v-for="n in graph.nodes" :key="n.id" :value="n.id">{{ n.label }}</option>
          </select></label
        >
        <FieldForm
          :model="edge"
          :fields="edgeFields"
          :disabled="!draft"
          submit-label="添加连线"
          @submit="addEdge"
        />
        <h4>现有连线</h4>
        <div v-for="line in graph.edges" :key="line.id" class="record-card">
          {{ line.source }} → {{ line.target
          }}<small>{{ line.label }} {{ line.condition }} {{ line.keywords?.join('/') }}</small
          ><el-button
            link
            type="danger"
            :disabled="!draft"
            @click="graph.edges = graph.edges.filter((e) => e.id !== line.id)"
            >删除</el-button
          >
        </div>
      </aside>
    </div></el-dialog
  >
</template>

<style scoped>
.node-link-select {
  display: grid;
  gap: 4px;
  margin: 8px 0;
}
.node-link-select select {
  height: 32px;
  border: 1px solid #c7ced8;
  border-radius: 4px;
  background: white;
}
</style>
