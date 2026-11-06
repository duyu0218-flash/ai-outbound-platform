<script setup>
import { ref } from 'vue';
import { request } from '../session';
const props = defineProps({ callId: [Number, String], asset: Object });
const url = ref(''),
  error = ref(''),
  player = ref(),
  speed = ref(1);
async function link(download = false) {
  return (
    await request(
      '/api/v1/calls/' +
        props.callId +
        '/recordings/' +
        props.asset.id +
        '/link?download=' +
        download,
    )
  ).url;
}
async function load() {
  try {
    url.value = await link();
  } catch (e) {
    error.value = e.message;
  }
}
async function copy() {
  try {
    await navigator.clipboard.writeText(await link());
  } catch (e) {
    error.value = e.message;
  }
}
async function download() {
  try {
    const href = await link(true);
    const anchor = document.createElement('a');
    anchor.href = href;
    anchor.download = 'recording-' + props.asset.id;
    anchor.rel = 'noopener';
    anchor.target = '_blank';
    anchor.click();
  } catch (e) {
    error.value = e.message;
  }
}
</script>
<template>
  <el-alert v-if="error" :title="error" type="error" /><el-button
    :disabled="asset.state !== 'available'"
    @click="load"
    >载入录音</el-button
  ><audio
    v-if="url"
    ref="player"
    :src="url"
    controls
    preload="none"
    :aria-label="'通话录音' + asset.id"
  /><el-select
    v-if="url"
    v-model="speed"
    aria-label="录音播放速度"
    @change="player.playbackRate = speed"
    ><el-option
      v-for="n in [0.75, 1, 1.25, 1.5, 2]"
      :key="n"
      :value="n"
      :label="n + '倍速'" /></el-select
  ><el-button :disabled="asset.state !== 'available'" @click="download">下载录音</el-button
  ><el-button :disabled="asset.state !== 'available'" @click="copy">复制录音链接</el-button>
</template>
