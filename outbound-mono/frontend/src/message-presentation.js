export const messageState = (value) =>
  ({
    QUEUED: '待提交',
    SUBMITTING: '提交中',
    ACCEPTED: '已受理',
    DELIVERED: '已送达',
    FAILED: '发送失败',
    UNKNOWN: '结果待核对',
    EXPIRED: '已过期',
    CANCELLED: '已取消',
    REJECTED: '已拒绝',
    COMPLETED: '已完成提交',
    PROCESSING: '处理中',
  })[String(value || '').toUpperCase()] || '状态待核对';
export const billingState = (value) =>
  ({ HELD: '额度已冻结', SETTLED: '已结算', RELEASED: '额度已释放', REFUNDED: '已退款' })[value] ||
  '账务待核对';
export const messageForm = (value) =>
  ({ sms: '普通短信', rcs: '5G消息', yuexin: '阅信', ip: '手机厂商消息', push: '手机消息' })[
    value
  ] || '消息';
export function microAmount(value) {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return '未计价';
  return (
    (Number(value) / 1000000).toLocaleString('zh-CN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 6,
    }) + ' 元'
  );
}
export function messageEvent(value) {
  if (value === 'message.interaction') return '消息互动';
  const state = String(value || '')
    .replace(/^message\./, '')
    .toUpperCase();
  if (state === 'REFUNDED') return '模拟费用已退款';
  return messageState(state) === '状态待核对' ? '其他消息事件' : '消息' + messageState(state);
}
