<script setup>
import { computed, ref, onBeforeUnmount } from 'vue';
import { session, request } from '../session';
import { useData } from '../data';
const props = defineProps({ scriptId: { type: [Number, String], required: true } });
const base = computed(() => '/api/v1/workspace/scripts/' + props.scriptId + '/audio'),
  feed = useData(() => base.value),
  error = ref(''),
  help = ref(false),
  selected = ref(''),
  busy = ref(false),
  recording = ref(false),
  clip = ref(null),
  url = ref('');
let recorder,
  stream,
  timer,
  disposed = false;
const rows = computed(() => feed.data.value || []),
  row = computed(() => rows.value.find((r) => r.key === selected.value));
function clearClip() {
  if (url.value) URL.revokeObjectURL(url.value);
  url.value = '';
  clip.value = null;
}
function choose(r) {
  if (recording.value || busy.value) return;
  clearClip();
  selected.value = r.key;
  error.value = '';
}
function stop() {
  if (recorder?.state === 'recording') recorder.stop();
  clearTimeout(timer);
}
async function begin() {
  if (!row.value || busy.value || recording.value) return;
  clearClip();
  busy.value = true;
  error.value = '';
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    if (disposed) {
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    recorder = new MediaRecorder(stream);
    const parts = [];
    recorder.ondataavailable = (e) => {
      if (e.data.size) parts.push(e.data);
    };
    recorder.onstop = () => {
      stream?.getTracks().forEach((t) => t.stop());
      recording.value = false;
      if (disposed) return;
      clip.value = new Blob(parts, { type: recorder.mimeType });
      url.value = URL.createObjectURL(clip.value);
    };
    recorder.start();
    recording.value = true;
    timer = setTimeout(stop, 60000);
  } catch (e) {
    stream?.getTracks().forEach((t) => t.stop());
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
function file(e) {
  const f = e.target.files[0];
  if (f) {
    clearClip();
    clip.value = f;
    url.value = URL.createObjectURL(f);
  }
  e.target.value = '';
}
async function save() {
  if (!row.value || !clip.value) return;
  busy.value = true;
  error.value = '';
  try {
    const form = new FormData();
    form.append('audio', clip.value, 'recording');
    form.append('revision', String(row.value.revision));
    await request(base.value + '/' + row.value.key, 'POST', form);
    clearClip();
    await feed.load();
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
async function play(r) {
  try {
    const res = await fetch(base.value + '/' + r.key, {
      headers: { Authorization: 'Bearer ' + session.token },
    });
    if (!res.ok) throw Error('读取录音失败');
    const b = await res.blob();
    if (disposed) return;
    clearClip();
    url.value = URL.createObjectURL(b);
  } catch (e) {
    error.value = e.message;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  stop();
  stream?.getTracks().forEach((t) => t.stop());
  clearClip();
});
</script>
<template>
  <el-card header="个性录音"
    ><el-button @click="help = !help">如何录音</el-button>
    <p v-if="help">
      先保存话术，选择需要录制的片段。允许麦克风并开始录音，停止后试听并保存。也可以上传 WAV、WebM
      或 M4A，单个文件上限 4 MB。修改片段文本后需要重新录制。
    </p>
    <el-button @click="feed.load()">检测录音覆盖率</el-button>
    <p>
      录音覆盖率 {{ rows.filter((r) => r.revision > 0).length }} / {{ rows.length }} ·
      {{
        rows.length
          ? ((100 * rows.filter((r) => r.revision > 0).length) / rows.length).toFixed(2) + '%'
          : '—'
      }}
    </p>
    <el-alert v-if="error || feed.error.value" :title="error || feed.error.value" type="error" />
    <el-table :data="rows" empty-text="请先保存开场白、结束语或流程节点播报内容"
      ><el-table-column prop="text" label="话术片段" /><el-table-column label="录音"
        ><template #default="{ row: r }"
          ><el-button :disabled="recording || busy" @click="choose(r)">选择片段</el-button
          ><el-button
            :disabled="!r.revision || recording || busy"
            @click="
              choose(r);
              play(r);
            "
            >试听已保存录音</el-button
          ></template
        ></el-table-column
      ></el-table
    >
    <template v-if="row"
      ><p>当前片段 {{ row.text }}</p>
      <el-button :disabled="recording || busy" @click="begin">开始录音</el-button
      ><el-button :disabled="!recording" @click="stop">停止录音</el-button
      ><input
        type="file"
        aria-label="上传片段录音"
        :disabled="recording || busy"
        accept="audio/wav,audio/webm,audio/mp4,.m4a"
        @change="file"
      /><audio v-if="url" :src="url" controls /><el-button
        :disabled="!clip || busy || recording"
        @click="save"
        >保存片段录音</el-button
      ></template
    ></el-card
  >
</template>
