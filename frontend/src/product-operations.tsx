import { useEffect, useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, InputNumber, Select, Space, Switch, Table, Tabs, Tag, Typography, message, Popconfirm } from 'antd'
import { apiRequest, formatDate } from './api'
import { useAuth } from './auth'

type Policy = Record<string, string | number | boolean | object> & { name: string }
type PolicyResult = { policy: Policy; version_id: number | null; history?: { id: number; policy: Policy; created_at: string }[] }
type Appointment = { id: string; phone: string; scheduled_at: string; state: string; revision: number }
type Work = { id: string; kind: string; phone: string; state: string; detail_json: string }
type Outcome = { call_id: string; phone: string; attempt: number; policy_version_id: number; status: string; data: { outcome?: string; answer_kind?: string; slots?: Record<string, { value: string; confirmed: boolean }> } }
type Funnel = { answered_attempts: number; answered_phones: number; definitions: string; groups: { outcome: string; answer_kind: string; attempts: number; unique_phones: number }[] }
type Probe = { trace: { utterance: string; intent: string; action: string; reply: string }[] }

export function ProductOperationsPage() {
  const { token, user } = useAuth()
  const client = useQueryClient()
  const [form] = Form.useForm<Policy>()
  const [campaign, setCampaign] = useState<number | undefined>()
  const [sample, setSample] = useState('不用转人工')
  const [probe, setProbe] = useState<Probe>()
  const [knowledge, setKnowledge] = useState('')
  const [title, setTitle] = useState('')
  const [source, setSource] = useState('')
  const [faqText, setFaqText] = useState('{}')
  const [pages, setPages] = useState({ appointments: 1, work: 1, outcomes: 1 })
  const [search, setSearch] = useState('')
  const [hits, setHits] = useState<{ id: string; title: string; content: string }[]>([])
  const [appointmentTimes, setAppointmentTimes] = useState<Record<string, string>>({})
  const scope = [user?.tenant_id, token, campaign]
  const request = <T,>(path: string, method = 'GET', body?: unknown) => apiRequest<T>(`/api/v1/product${path}`, { method, ...(body ? { body: JSON.stringify(body) } : {}) }, token)
  const policy = useQuery({ queryKey: ['product-policy', ...scope], queryFn: () => request<PolicyResult>(`/policy${campaign ? `?campaign_id=${campaign}` : ''}`), enabled: !!token })
  const appointments = useQuery({ queryKey: ['product-appointments', ...scope, pages.appointments], queryFn: () => request<Appointment[]>(`/appointments?page=${pages.appointments}`), enabled: !!token })
  const work = useQuery({ queryKey: ['product-work', ...scope, pages.work], queryFn: () => request<Work[]>(`/work-items?page=${pages.work}`), enabled: !!token })
  const outcomes = useQuery({ queryKey: ['product-outcomes', ...scope, pages.outcomes], queryFn: () => request<Outcome[]>(`/outcomes?page=${pages.outcomes}`), enabled: !!token })
  useEffect(() => { if (policy.data) { form.resetFields(); form.setFieldsValue(policy.data.policy); setFaqText(JSON.stringify(policy.data.policy.faqs || {}, null, 2)) } }, [policy.data, form])
  const refresh = () => { void client.invalidateQueries({ queryKey: ['product-policy'] }); void client.invalidateQueries({ queryKey: ['product-appointments'] }); void client.invalidateQueries({ queryKey: ['product-work'] }); void client.invalidateQueries({ queryKey: ['product-outcomes'] }); void client.invalidateQueries({ queryKey: ['product-funnel'] }) }
  const save = useMutation({ mutationFn: async (values: Policy) => request('/policy', 'POST', { campaign_id: campaign || null, expected_version_id: policy.data?.version_id ?? null, policy: { ...policy.data?.policy, ...values, faqs: JSON.parse(faqText) } }), onSuccess: () => { message.success('策略已发布，下次通话生效'); refresh() }, onError: (e: Error) => message.error(e.message) })
  const test = useMutation({ mutationFn: async () => request<Probe>('/simulate', 'POST', { policy: { ...policy.data?.policy, ...await form.validateFields(), faqs: JSON.parse(faqText) }, utterances: sample.split('\n').filter(Boolean) }), onSuccess: setProbe, onError: (e: Error) => message.error(e.message) })
  const editAppointment = async (row: Appointment, cancel: boolean) => {
    try { await request(`/appointments/${row.id}`, 'PATCH', { revision: row.revision, cancel, ...(!cancel ? { scheduled_at: new Date(appointmentTimes[row.id]).toISOString() } : {}) }); message.success('预约已更新'); refresh() } catch (e) { message.error(String(e)) }
  }
  const funnel = useQuery({ queryKey: ['product-funnel', ...scope], queryFn: () => request<Funnel>('/funnel'), enabled: !!token })
  const error = funnel.error || policy.error || appointments.error || work.error || outcomes.error
  return <Space direction="vertical" size="middle" style={{ width: '100%' }}>
    <div className="page-title-row"><Typography.Title level={2}>业务交付策略</Typography.Title></div>
    {error && <Alert type="error" showIcon message={String(error)} />}
    <Space><span>活动编号（留空为租户默认）</span><InputNumber aria-label="活动编号" min={1} value={campaign} onChange={v => setCampaign(v || undefined)} /><Tag>当前版本 {policy.data?.version_id || '默认'}</Tag><Button onClick={refresh}>刷新</Button></Space>
    <Form form={form} layout="vertical" onFinish={values => save.mutate(values)}>
      <Tabs destroyOnHidden={false} items={[
        { key: 'policy', label: '对话与异常处理', children: <Card loading={policy.isLoading}>
          <Form.Item name="name" label="策略名称" rules={[{ required: true }]}><Input /></Form.Item>
          <Space align="start" wrap>
            <Form.Item name="silence_seconds" label="无应答等待（秒）"><InputNumber min={3} max={120} /></Form.Item>
            <Form.Item name="wait_seconds" label="客户要求稍等（秒）"><InputNumber min={5} max={180} /></Form.Item>
            <Form.Item name="max_clarifications" label="最多澄清次数"><InputNumber min={1} max={5} /></Form.Item>
            <Form.Item name="confidence_threshold" label="低于此置信度先确认"><InputNumber min={0} max={1} step={0.05} /></Form.Item>
          </Space>
          <Form.Item name="silence_prompt" label="无应答提醒"><Input /></Form.Item>
          <Form.Item name="clarify_prompt" label="听不清时的提示"><Input /></Form.Item>
          <Form.Item name="model_wait_seconds" label="等待模型多久后提示（秒）"><InputNumber min={1} max={20} /></Form.Item><Form.Item name="model_wait_prompt" label="模型等待提示"><Input /></Form.Item>
          <Form.Item name="failure_prompt" label="服务失败结束语"><Input /></Form.Item>
          <Typography.Paragraph>全局问答：每个问题对应一段回答，回答后返回尚未完成的采集问题。</Typography.Paragraph><Input.TextArea aria-label="全局问答" rows={4} value={faqText} onChange={e => setFaqText(e.target.value)} placeholder={'{"你们是谁":"我们是客服团队。"}'} />
          <Form.Item name="machine_action" label="电话助手或信箱提示"><Select options={[{ value: 'confirm', label: '先确认是否真人，再决定结束' }, { value: 'end', label: '识别到提示后结束' }]} /></Form.Item>
          <Space wrap><Form.Item name="allow_interruptions" label="允许客户打断" valuePropName="checked"><Switch /></Form.Item><Form.Item name="min_interrupt_chars" label="有效打断最少字数"><InputNumber min={1} max={10} /></Form.Item><Form.Item name="opening_delay_ms" label="开场延迟（毫秒）"><InputNumber min={0} max={5000} /></Form.Item><Form.Item name="sentence_silence_ms" label="断句静默（毫秒）"><InputNumber min={200} max={2000} /></Form.Item></Space>
          <Space wrap><Form.Item name="language" label="语言"><Select style={{ width: 160 }} options={['zh-CN', 'en-US', 'zh-HK'].map(value => ({ value, label: value }))} /></Form.Item><Form.Item name="voice" label="已配置的音色"><Input /></Form.Item><Form.Item name="vocabulary_id" label="行业热词表编号"><Input /></Form.Item></Space>
        </Card> },
        { key: 'slots', label: '信息采集', children: <Card><Typography.Paragraph>字段需要确认后才计入完整信息；合格取值为空时，仅表示信息已采集，不自动判为合格线索。</Typography.Paragraph>
          <Form.List name="slots">{(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>{fields.map(field => <Card key={field.key} size="small" extra={<Button danger onClick={() => remove(field.name)}>删除字段</Button>}>
            <Space wrap><Form.Item name={[field.name, 'key']} label="字段标识" rules={[{ required: true, pattern: /^[a-z][a-z0-9_]{0,39}$/ }]}><Input /></Form.Item><Form.Item name={[field.name, 'label']} label="名称" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name={[field.name, 'kind']} label="类型"><Select style={{ width: 140 }} options={[['text','文本'],['integer','整数'],['choice','选项'],['date','日期'],['digits','按键数字']].map(([value,label]) => ({ value,label }))} /></Form.Item><Form.Item name={[field.name, 'required']} label="必填" valuePropName="checked"><Switch /></Form.Item><Form.Item name={[field.name, 'confirm']} label="复述确认" valuePropName="checked"><Switch /></Form.Item></Space>
            <Form.Item name={[field.name, 'question']} label="询问话术" rules={[{ required: true }]}><Input /></Form.Item><Form.Item name={[field.name, 'choices']} label="可选值"><Select mode="tags" /></Form.Item><Form.Item name={[field.name, 'qualifies']} label="合格取值"><Select mode="tags" /></Form.Item>
          </Card>)}<Button onClick={() => add({ key: '', label: '', question: '', kind: 'text', required: true, confirm: true, choices: [], qualifies: [] })}>添加字段</Button></Space>}</Form.List>
        </Card> },
        { key: 'handoff', label: '转人工与时间安排', children: <Card><Form.Item name="handoff_agent_ids" label="可接待的坐席编号" getValueFromEvent={v => v.map(Number)}><Select mode="tags" /></Form.Item><Form.Item name="handoff_wait_seconds" label="最长等待（秒）"><InputNumber min={5} max={300} /></Form.Item><Form.Item name="handoff_prompt" label="等待提示"><Input /></Form.Item><Form.Item name="handoff_timeout_prompt" label="等待超时提示"><Input /></Form.Item><Form.Item name="handoff_fallback" label="超时处理"><Select options={[{ value: 'end', label: '说明后结束通话' }, { value: 'ai', label: '返回 AI 继续沟通' }]} /></Form.Item><Space wrap><Form.Item name="timezone" label="服务时区"><Input /></Form.Item><Form.Item name="start_hour" label="开始时刻"><InputNumber min={0} max={23} /></Form.Item><Form.Item name="end_hour" label="结束时刻"><InputNumber min={1} max={24} /></Form.Item></Space><Form.Item name="weekdays" label="工作日"><Select mode="multiple" options={['星期一','星期二','星期三','星期四','星期五','星期六','星期日'].map((label,value) => ({ label,value }))} /></Form.Item><Form.Item name="holidays" label="不服务日期（年-月-日）"><Select mode="tags" /></Form.Item>{['busy','no_answer','voicemail','failed'].map((key, i) => <Form.Item key={key} name={['retry_seconds',key]} label={`${['忙线','无人接听','信箱','临时失败'][i]}重拨间隔（秒）`}><InputNumber min={60} max={604800} /></Form.Item>)}</Card> },
        { key: 'test', label: '试跑与版本', children: <Card><Typography.Paragraph>每行输入一轮客户回答。试跑使用实际业务规则，不拨号、不调用模型、不发送短信；需要模型理解的步骤会明确标出。</Typography.Paragraph><Input.TextArea aria-label="客户对话样例" rows={6} value={sample} onChange={e => setSample(e.target.value)} /><Button loading={test.isPending} onClick={() => test.mutate()}>运行对话试跑</Button><Table size="small" pagination={false} rowKey={(_, index) => String(index)} dataSource={probe?.trace} columns={[{ title: '客户说法', dataIndex: 'utterance' },{ title: '意图', dataIndex: 'intent' },{ title: '动作', dataIndex: 'action' },{ title: '回复', dataIndex: 'reply' }]} /><Table rowKey="id" size="small" dataSource={policy.data?.history} columns={[{ title: '版本', dataIndex: 'id' },{ title: '发布时间', dataIndex: 'created_at', render: formatDate },{ title: '操作', render: (_, row) => <Button onClick={() => { form.setFieldsValue(row.policy); setFaqText(JSON.stringify(row.policy.faqs || {}, null, 2)); message.info('旧版已载入，发布后成为新版本') }}>载入此版</Button> }]} /></Card> },
      ]} />
      <Button type="primary" htmlType="submit" loading={save.isPending} disabled={!policy.data}>发布策略</Button>
    </Form>
    <Tabs items={[
      { key: 'appointments', label: '预约回拨', children: <Table rowKey="id" pagination={{ current: pages.appointments, pageSize: 50, total: (pages.appointments - 1) * 50 + (appointments.data?.length || 0) + (appointments.data?.length === 50 ? 1 : 0), showSizeChanger: false, onChange: page => setPages({ ...pages, appointments: page }) }} dataSource={appointments.data} columns={[{ title: '号码', dataIndex: 'phone' },{ title: '预约时间', dataIndex: 'scheduled_at', render: formatDate },{ title: '状态', dataIndex: 'state' },{ title: '修改', render: (_, row) => <Space><Input type="datetime-local" aria-label={`修改预约${row.id}`} disabled={row.state !== 'confirmed'} onChange={e => setAppointmentTimes({ ...appointmentTimes, [row.id]: e.target.value })} /><Button disabled={row.state !== 'confirmed' || !appointmentTimes[row.id]} onClick={() => void editAppointment(row, false)}>保存时间</Button><Popconfirm title="取消这次预约？" onConfirm={() => editAppointment(row, true)}><Button danger disabled={row.state !== 'confirmed'}>取消预约</Button></Popconfirm></Space> }]} /> },
      { key: 'work', label: '人工跟进', children: <Table rowKey="id" pagination={{ current: pages.work, pageSize: 50, total: (pages.work - 1) * 50 + (work.data?.length || 0) + (work.data?.length === 50 ? 1 : 0), showSizeChanger: false, onChange: page => setPages({ ...pages, work: page }) }} dataSource={work.data} columns={[{ title: '号码', dataIndex: 'phone' },{ title: '来源', dataIndex: 'kind' },{ title: '状态', dataIndex: 'state' },{ title: '内容', dataIndex: 'detail_json', render: value => { try { const d = JSON.parse(value); return d.text || d.error || d.appointment_id || '-' } catch { return '-' } } },{ title: '操作', render: (_, row) => <Button disabled={row.state === 'completed'} onClick={async () => { try { await request(`/work-items/${row.id}`, 'PATCH', { state: 'completed' }); refresh() } catch (e) { message.error(String(e)) } }}>标记完成</Button> }]} /> },
      { key: 'funnel', label: '交付统计', children: <Card><Typography.Paragraph>最近 7 天接通尝试 {funnel.data?.answered_attempts ?? '-'} 次，接通号码 {funnel.data?.answered_phones ?? '-'} 个。</Typography.Paragraph><Typography.Paragraph>{funnel.data?.definitions}</Typography.Paragraph><Table rowKey={row => `${row.outcome}:${row.answer_kind}`} dataSource={funnel.data?.groups} columns={[{ title: '业务结果', dataIndex: 'outcome' },{ title: '接听类型', dataIndex: 'answer_kind' },{ title: '尝试次数', dataIndex: 'attempts' },{ title: '组内唯一号码', dataIndex: 'unique_phones' }]} /></Card> },
      { key: 'outcomes', label: '交付结果', children: <Table rowKey={row => `${row.call_id}:${row.attempt}`} pagination={{ current: pages.outcomes, pageSize: 50, total: (pages.outcomes - 1) * 50 + (outcomes.data?.length || 0) + (outcomes.data?.length === 50 ? 1 : 0), showSizeChanger: false, onChange: page => setPages({ ...pages, outcomes: page }) }} dataSource={outcomes.data} columns={[{ title: '号码', dataIndex: 'phone' },{ title: '拨打轮次', dataIndex: 'attempt' },{ title: '策略版本', dataIndex: 'policy_version_id' },{ title: '接听类型', render: (_, row) => row.data.answer_kind || 'unknown' },{ title: '业务结果', render: (_, row) => row.data.outcome || '待确认' },{ title: '已采集信息', render: (_, row) => Object.entries(row.data.slots || {}).map(([key, slot]) => `${key}：${slot.value}${slot.confirmed ? '（已确认）' : ''}`).join('；') }]} /> },
      { key: 'knowledge', label: '知识导入与检索测试', children: <Card><Space direction="vertical" style={{ width: '100%' }}><Input placeholder="知识标题" aria-label="知识标题" value={title} onChange={e => setTitle(e.target.value)} /><input aria-label="导入文本文件" type="file" accept=".txt,.md" onChange={async e => { const file = e.target.files?.[0]; if (file) { if (file.size > 150000) { message.error('文件过大，请分段导入'); return } setTitle(file.name); setKnowledge(await file.text()) } }} /><Input aria-label="知识出处" placeholder="知识出处或文档编号" value={source} onChange={e => setSource(e.target.value)} /><Input.TextArea aria-label="知识正文" rows={5} value={knowledge} onChange={e => setKnowledge(e.target.value)} /><Button disabled={!title || !knowledge} onClick={async () => { try { await request('/knowledge/import','POST',{ title,content:knowledge,source,campaign_id:campaign || null }); message.success('知识已导入'); setKnowledge('') } catch (e) { message.error(String(e)) } }}>保存知识</Button><Input aria-label="检索问题" value={search} onChange={e => setSearch(e.target.value)} /><Button disabled={!search} onClick={async () => { try { setHits(await request('/knowledge/search','POST',{ query:search,campaign_id:campaign || null })) } catch (e) { message.error(String(e)) } }}>测试检索</Button><Table rowKey="id" dataSource={hits} columns={[{ title:'标题',dataIndex:'title' },{ title:'匹配内容',dataIndex:'content' }]} /></Space></Card> },
    ]} />
  </Space>
}
