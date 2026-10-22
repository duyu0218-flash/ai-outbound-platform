import { ref, watch, onUnmounted, toValue } from 'vue';
import { session, request } from './session';
import { ElMessage, ElMessageBox } from 'element-plus';
export function useData(path, interval = 0) {
  const data = ref(null),
    error = ref(''),
    loading = ref(false);
  let serial = 0,
    controller;
  const load = async () => {
    const url = toValue(path),
      current = ++serial,
      epoch = session.epoch;
    controller?.abort();
    if (!url || !session.token) {
      data.value = null;
      return;
    }
    controller = new AbortController();
    loading.value = true;
    try {
      const value = await request(url, 'GET', undefined, { signal: controller.signal });
      if (current === serial && epoch === session.epoch) {
        data.value = value;
        error.value = '';
      }
    } catch (e) {
      if (current === serial && e.name !== 'AbortError') error.value = e.message;
    } finally {
      if (current === serial) loading.value = false;
    }
  };
  watch(
    () => [toValue(path), session.epoch],
    () => {
      data.value = null;
      load();
    },
    { immediate: true },
  );
  const timer = interval ? setInterval(load, interval) : null;
  onUnmounted(() => {
    serial++;
    controller?.abort();
    if (timer) clearInterval(timer);
  });
  return { data, error, loading, load };
}
export async function action(work, { confirm, refresh, success = 'operationSuccess' } = {}) {
  try {
    if (confirm) await ElMessageBox.confirm(confirm, '确认', { type: 'warning' });
    const result = await work();
    if (success) ElMessage.success(success === 'operationSuccess' ? '操作成功' : success);
    if (refresh) await refresh();
    return result;
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || String(error));
    throw error;
  }
}
export const modes = ['ai_only', 'ai_handoff', 'human_only', 'ai_with_sms'];
export const terminals = ['completed', 'failed', 'no_answer', 'busy', 'voicemail'];
export const states = [
  'created',
  'queued',
  'dialing',
  'answered',
  'in_ai',
  'waiting_human',
  'handoff_transferring',
  'in_human',
  ...terminals,
];
export const fields = (...items) =>
  items.map((item) => (typeof item === 'string' ? { key: item, label: item } : item));
