<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { session } from '../session';
import { useData } from '../data';
const props = defineProps({ scriptId: [String, Number], opening: String, reply: String });
const emit = defineEmits(['transcript']),
  mode = ref('record'),
  message = ref(''),
  player = ref(null),
  playing = ref(false),
  voices = ref([]),
  voice = ref(''),
  heard = ref(''),
  listening = ref(false);
const clips = useData(() =>
  props.scriptId ? '/api/v1/workspace/scripts/' + props.scriptId + '/audio' : '',
);
const phrase = computed(() => props.reply || props.opening || ''),
  match = computed(() => clips.data.value?.find((r) => r.text === phrase.value && r.revision));
let obj = '',
  recognizer,
  generation = 0;
function listVoices() {
  voices.value = (window.speechSynthesis?.getVoices() || []).filter((v) => v.localService);
  if (!voices.value.some((v) => v.voiceURI === voice.value))
    voice.value =
      (voices.value.find((v) => /^zh/i.test(v.lang)) || voices.value[0])?.voiceURI || '';
}
function stop() {
  generation++;
  player.value?.pause();
  window.speechSynthesis?.cancel();
  if (obj) URL.revokeObjectURL(obj);
  obj = '';
  playing.value = false;
}
async function speak() {
  stop();
  const id = generation;
  message.value = '';
  if (!phrase.value) {
    message.value = '当前轮没有待播文本';
    return;
  }
  try {
    if (mode.value === 'record' && match.value) {
      const r = await fetch(
        '/api/v1/workspace/scripts/' + props.scriptId + '/audio/' + match.value.key,
        { headers: { Authorization: 'Bearer ' + session.token } },
      );
      if (!r.ok) throw Error('无法读取真人录音');
      const buf = await r.blob();
      if (id !== generation) return;
      obj = URL.createObjectURL(buf);
      player.value.src = obj;
      await player.value.play();
      if (id !== generation) return;
      playing.value = true;
      message.value = '正在播放已保存录音';
      return;
    }
    listVoices();
    const engine = voices.value.find((v) => v.voiceURI === voice.value);
    if (!engine)
      throw Error(
        mode.value === 'record'
          ? '该句缺少录音，当前设备也没有离线合成音色'
          : '当前设备没有可用的离线合成音色',
      );
    const u = new SpeechSynthesisUtterance(phrase.value);
    u.voice = engine;
    u.lang = engine.lang;
    u.onstart = () => {
      if (generation === id) {
        playing.value = true;
        message.value =
          mode.value === 'record' ? '缺少该句录音，已回退至本机合成' : '正在使用本机合成音';
      }
    };
    u.onend = () => {
      if (generation === id) playing.value = false;
    };
    u.onerror = () => {
      if (generation === id) {
        message.value = '本机语音播放失败';
        playing.value = false;
      }
    };
    window.speechSynthesis.speak(u);
  } catch (e) {
    message.value = e.message;
  }
}
async function listen() {
  const R = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!R || !('processLocally' in R.prototype)) {
    message.value = '当前浏览器不支持离线识别，可填写听写文本继续调试。';
    return;
  }
  try {
    if (
      R.available &&
      (await R.available({ langs: ['zh-CN'], processLocally: true })) !== 'available'
    ) {
      message.value = '设备未安装中文离线识别语言包，可填写听写文本继续调试。';
      return;
    }
    recognizer = new R();
    recognizer.processLocally = true;
    recognizer.lang = 'zh-CN';
    recognizer.onresult = (e) => {
      heard.value = e.results[0][0].transcript;
    };
    recognizer.onend = () => {
      listening.value = false;
    };
    recognizer.onerror = (e) => {
      message.value = '离线识别失败：' + e.error;
      listening.value = false;
    };
    recognizer.start();
    listening.value = true;
  } catch (e) {
    message.value = e.message;
  }
}
onMounted(() => {
  listVoices();
  window.speechSynthesis?.addEventListener('voiceschanged', listVoices);
});
onBeforeUnmount(() => {
  stop();
  recognizer?.abort();
  window.speechSynthesis?.removeEventListener('voiceschanged', listVoices);
});
</script>
<template>
  <el-card header="语音调试配置"
    ><label
      >语音模式<select v-model="mode" aria-label="调试语音模式" @change="stop">
        <option value="record">真人录音</option>
        <option value="synth">合成音</option>
      </select></label
    >
    <label v-if="mode === 'synth'"
      >本机音色<select v-model="voice" aria-label="本机音色">
        <option v-for="v in voices" :key="v.voiceURI" :value="v.voiceURI">
          {{ v.name }}（{{ v.lang }}）
        </option>
      </select></label
    >
    <p>当前待播文本 {{ phrase || '请先选择已发布的话术' }}</p>
    <el-button @click="clips.load()">检测录音覆盖率</el-button>
    <p>
      已录 {{ (clips.data.value || []).filter((r) => r.revision).length }} /
      {{ (clips.data.value || []).length }} 段
    </p>
    <p v-for="r in (clips.data.value || []).filter((r) => !r.revision)" :key="r.key">
      缺少录音 · {{ r.text }}
    </p>
    <el-button @click="speak" :disabled="!phrase">播放当前回复</el-button
    ><el-button @click="stop" :disabled="!playing">停止播放</el-button
    ><audio ref="player" @ended="playing = false" />
    <p>{{ message }}</p>
    <el-button @click="listen" :disabled="listening">本机语音输入</el-button
    ><el-button :disabled="!listening" @click="recognizer?.stop()">结束听写</el-button
    ><el-input aria-label="确认听写文本" v-model="heard" /><el-button
      :disabled="!heard.trim()"
      @click="
        emit('transcript', heard.trim());
        heard = '';
      "
      >采用听写文本</el-button
    ></el-card
  >
</template>
