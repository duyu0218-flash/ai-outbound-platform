import {
  CheckCircleOutlined,
  CustomerServiceOutlined,
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  PhoneOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  SearchOutlined,
  SoundOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useNavigate } from 'react-router-dom'
import { apiRequest, formatDate } from './api'
import { useAuth } from './auth'
import { setLanguage } from './i18n'
import type { AdminDashboard, CallEvent, CallMode, CallSession, Campaign, Contact, Role, ScriptTemplate } from './types'

const { Title, Text, Paragraph } = Typography

function PageTitle({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return (
    <div className="page-title-row">
      <div><Title level={2}>{title}</Title><Text type="secondary">{description}</Text></div>
      {action && <div>{action}</div>}
    </div>
  )
}

function useSecureQuery<T>(key: string[], path: string, enabled = true) {
  const { token } = useAuth()
  return useQuery({ queryKey: key, queryFn: () => apiRequest<T>(path, {}, token), enabled: Boolean(token) && enabled })
}

const modeOptions = [
  { value: 'ai_handoff', labelKey: 'aiHandoff' },
  { value: 'ai_only', labelKey: 'aiOnly' },
  { value: 'human_only', labelKey: 'humanOnly' },
  { value: 'ai_with_sms', labelKey: 'aiWithSms' },
  { value: 'mixed_human_first', labelKey: 'mixedHumanFirst' },
] as const

function StatusTag({ status }: { status: string }) {
  const { t } = useTranslation()
  const colors: Record<string, string> = { running: 'processing', completed: 'success', failed: 'error', draft: 'default', answered: 'success', in_ai: 'blue', waiting_human: 'orange', dialing: 'processing', queued: 'cyan' }
  return <Tag color={colors[status] || 'default'}>{t(status, { defaultValue: status })}</Tag>
}

export function LoginPage({ role }: { role: Role }) {
  const { t, i18n } = useTranslation()
  const { login, user } = useAuth()
  const navigate = useNavigate()
  const [submitting, setSubmitting] = useState(false)

  if (user) return <Navigate to={user.role === 'admin' && role === 'admin' ? '/admin' : '/agent'} replace />

  const submit = async (values: { username: string; password: string }) => {
    setSubmitting(true)
    try {
      const profile = await login(values.username, values.password, role)
      navigate(profile.role === 'admin' && role === 'admin' ? '/admin' : '/agent', { replace: true })
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('loginFailed'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-visual">
        <div className="login-logo"><SoundOutlined /></div>
        <Title>{t('product')}</Title>
        <Paragraph>{role === 'admin' ? t('overviewHint') : t('workbenchHint')}</Paragraph>
        <div className="login-feature-list">
          <span><CheckCircleOutlined /> {t('aiOnly')}</span>
          <span><CheckCircleOutlined /> {t('aiHandoff')}</span>
          <span><CheckCircleOutlined /> {t('humanOnly')}</span>
          <span><CheckCircleOutlined /> {t('aiWithSms')}</span>
        </div>
      </div>
      <Card className="login-card" bordered={false}>
        <div className="login-card-top">
          <Tag color={role === 'admin' ? 'blue' : 'cyan'}>{role === 'admin' ? t('adminPortal') : t('agentPortal')}</Tag>
          <Select
            aria-label={t('language')}
            variant="borderless"
            value={i18n.language.startsWith('en') ? 'en-US' : 'zh-CN'}
            options={[{ value: 'zh-CN', label: '中文' }, { value: 'en-US', label: 'English' }]}
            onChange={setLanguage}
          />
        </div>
        <Title level={2}>{t('welcomeBack')}</Title>
        <Paragraph type="secondary">{t('loginHint')}</Paragraph>
        <Form layout="vertical" size="large" initialValues={{ username: role === 'admin' ? 'admin' : '1001@test', password: '12345678' }} onFinish={submit}>
          <Form.Item label={t('username')} name="username" rules={[{ required: true }]}><Input autoComplete="username" /></Form.Item>
          <Form.Item label={t('password')} name="password" rules={[{ required: true }]}><Input.Password autoComplete="current-password" /></Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>{t('signIn')}</Button>
        </Form>
        <Alert className="demo-account" type="info" showIcon message={role === 'admin' ? t('adminDemo') : t('agentDemo')} />
        <Button type="link" block onClick={() => navigate(role === 'admin' ? '/agent/login' : '/admin/login')}>
          {role === 'admin' ? t('agentPortal') : t('adminPortal')}
        </Button>
      </Card>
    </div>
  )
}

export function DashboardPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const dashboard = useSecureQuery<AdminDashboard>(['admin-dashboard'], '/api/v1/admin/dashboard')
  const calls = useSecureQuery<CallSession[]>(['calls', 'dashboard'], '/api/v1/calls?page=1&size=200')
  const loading = dashboard.isLoading || calls.isLoading
  const stats = [
    { title: t('totalContacts'), value: dashboard.data?.stats.contacts || 0, icon: <TeamOutlined />, color: 'blue' },
    { title: t('activeScripts'), value: dashboard.data?.stats.active_scripts || 0, icon: <FileTextOutlined />, color: 'purple' },
    { title: t('totalCampaigns'), value: dashboard.data?.stats.campaigns || 0, icon: <SoundOutlined />, color: 'orange' },
    { title: t('totalCalls'), value: dashboard.data?.stats.calls || 0, icon: <PhoneOutlined />, color: 'green' },
  ]
  return (
    <>
      <PageTitle title={t('overview')} description={t('overviewHint')} />
      <Row gutter={[16, 16]}>
        {stats.map((item) => <Col xs={24} sm={12} xl={6} key={item.title}><Card loading={loading} className="metric-card"><div className={`metric-icon ${item.color}`}>{item.icon}</div><Statistic title={item.title} value={item.value} /></Card></Col>)}
      </Row>
      <Row gutter={[16, 16]} className="dashboard-grid">
        <Col xs={24} xl={16}>
          <Card title={t('recentCalls')} extra={<Button type="link" onClick={() => navigate('/admin/calls')}>{t('details')}</Button>}>
            <Table<CallSession>
              rowKey="id" size="middle" pagination={false} dataSource={(calls.data || []).slice(0, 6)} locale={{ emptyText: t('empty') }}
              columns={[
                { title: t('phone'), dataIndex: 'phone' },
                { title: t('mode'), dataIndex: 'mode', render: (value) => t(modeOptions.find((item) => item.value === value)?.labelKey || value) },
                { title: t('status'), dataIndex: 'status', render: (value) => <StatusTag status={value} /> },
                { title: t('createdAt'), dataIndex: 'created_at', render: formatDate },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title={t('quickActions')} className="quick-actions">
            <Button icon={<TeamOutlined />} onClick={() => navigate('/admin/contacts')}>{t('addContact')}</Button>
            <Button icon={<FileTextOutlined />} onClick={() => navigate('/admin/scripts')}>{t('createScript')}</Button>
            <Button icon={<RocketOutlined />} onClick={() => navigate('/admin/campaigns')}>{t('createCampaign')}</Button>
            <Button type="primary" icon={<PhoneOutlined />} onClick={() => navigate('/admin/calls')}>{t('startCall')}</Button>
          </Card>
        </Col>
      </Row>
    </>
  )
}

interface ContactFormValues { phone: string; name?: string; tags?: string; consent_state: Contact['consent_state']; dnc?: boolean; timezone?: string }

export function ContactsPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [searchKeyword, setSearchKeyword] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Contact | null>(null)
  const [form] = Form.useForm<ContactFormValues>()
  const query = useSecureQuery<Contact[]>(['contacts', searchKeyword], `/api/v1/contacts?page=1&size=100${searchKeyword ? `&keyword=${encodeURIComponent(searchKeyword)}` : ''}`)
  const mutation = useMutation({
    mutationFn: (values: ContactFormValues) => apiRequest<Contact>(editing ? `/api/v1/contacts/${editing.id}` : '/api/v1/contacts', { method: editing ? 'PATCH' : 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); setEditing(null); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['contacts'] }) },
    onError: (error) => message.error(error.message),
  })
  const remove = useMutation({
    mutationFn: (id: number) => apiRequest(`/api/v1/contacts/${id}`, { method: 'DELETE' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['contacts'] }) },
    onError: (error) => message.error(error.message),
  })

  const openEdit = (contact?: Contact) => {
    setEditing(contact || null)
    form.setFieldsValue(contact || { consent_state: 'consented', dnc: false, timezone: 'Asia/Shanghai' })
    setModalOpen(true)
  }

  return (
    <>
      <PageTitle title={t('contacts')} description={t('contactHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>{t('addContact')}</Button>} />
      <Card>
        <div className="table-toolbar">
          <Input allowClear value={keyword} prefix={<SearchOutlined />} placeholder={`${t('phone')} / ${t('name')}`} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => setSearchKeyword(keyword)} />
          <Button type="primary" onClick={() => setSearchKeyword(keyword)}>{t('search')}</Button>
          <Button onClick={() => { setKeyword(''); setSearchKeyword('') }}>{t('reset')}</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button>
        </div>
        <Table<Contact> rowKey="id" loading={query.isLoading} dataSource={query.data || []} locale={{ emptyText: t('empty') }} scroll={{ x: 900 }} columns={[
          { title: 'ID', dataIndex: 'id', width: 70 },
          { title: t('phone'), dataIndex: 'phone', width: 160 },
          { title: t('contactName'), dataIndex: 'name', width: 150, render: (value) => value || '-' },
          { title: t('tags'), dataIndex: 'tags', render: (value) => value ? <Tag>{value}</Tag> : '-' },
          { title: t('consent'), dataIndex: 'consent_state', render: (value) => <StatusTag status={value} /> },
          { title: t('dnc'), dataIndex: 'dnc', width: 90, render: (value) => value ? <Tag color="red">{t('yes')}</Tag> : <Tag color="green">{t('no')}</Tag> },
          { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
          { title: t('actions'), key: 'actions', fixed: 'right', width: 130, render: (_, record) => <Space><Button type="text" icon={<EditOutlined />} onClick={() => openEdit(record)} /><Popconfirm title={t('confirmDelete')} onConfirm={() => remove.mutate(record.id)}><Button type="text" danger icon={<DeleteOutlined />} /></Popconfirm></Space> },
        ]} />
      </Card>
      <Modal title={editing ? t('edit') : t('addContact')} open={modalOpen} onCancel={() => { setModalOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={mutation.isPending} destroyOnHidden>
        <Form<ContactFormValues> form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('phone')} name="phone" rules={[{ required: !editing }]}><Input disabled={Boolean(editing)} /></Form.Item></Col><Col span={12}><Form.Item label={t('contactName')} name="name"><Input /></Form.Item></Col></Row>
          <Form.Item label={t('tags')} name="tags"><Input /></Form.Item>
          <Row gutter={12}><Col span={12}><Form.Item label={t('consent')} name="consent_state"><Select options={[{ value: 'consented', label: t('consented') }, { value: 'not_consented', label: t('notConsented') }, { value: 'revoked', label: t('revoked') }, { value: 'unknown', label: t('unknown') }]} /></Form.Item></Col><Col span={12}><Form.Item label={t('timezone')} name="timezone"><Input /></Form.Item></Col></Row>
          <Form.Item label={t('dnc')} name="dnc" valuePropName="checked"><Switch /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}

interface ScriptFormValues { name: string; content: string; category: string; description?: string; tags?: string; is_active: boolean }

export function ScriptsPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<ScriptTemplate[]>(['scripts'], '/api/v1/script-templates?page=1&size=100')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ScriptTemplate | null>(null)
  const [form] = Form.useForm<ScriptFormValues>()
  const mutation = useMutation({
    mutationFn: (values: ScriptFormValues) => apiRequest<ScriptTemplate>(editing ? `/api/v1/script-templates/${editing.id}` : '/api/v1/script-templates', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); setEditing(null); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['scripts'] }) },
    onError: (error) => message.error(error.message),
  })
  const toggle = useMutation({
    mutationFn: (item: ScriptTemplate) => apiRequest(`/api/v1/script-templates/${item.id}`, { method: 'PUT', body: JSON.stringify({ is_active: !item.is_active }) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['scripts'] }) },
    onError: (error) => message.error(error.message),
  })
  const openEdit = (item?: ScriptTemplate) => { setEditing(item || null); form.setFieldsValue(item || { category: 'default', is_active: true }); setModalOpen(true) }
  return (
    <>
      <PageTitle title={t('scripts')} description={t('scriptHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>{t('createScript')}</Button>} />
      <Row gutter={[16, 16]}>{(query.data || []).map((item) => <Col xs={24} lg={12} xl={8} key={item.id}><Card loading={query.isLoading} className="script-card" title={<Space><FileTextOutlined /><span>{item.name}</span></Space>} extra={<Switch size="small" checked={item.is_active} onChange={() => toggle.mutate(item)} />} actions={[<Button type="text" icon={<EditOutlined />} onClick={() => openEdit(item)}>{t('edit')}</Button>]}><Space wrap><Tag>{item.category}</Tag><Tag>v{item.version}</Tag>{item.tags && <Tag color="blue">{item.tags}</Tag>}</Space><Paragraph ellipsis={{ rows: 4, expandable: true }} className="script-preview">{item.content}</Paragraph><Text type="secondary">{formatDate(item.updated_at)}</Text></Card></Col>)}</Row>
      {!query.isLoading && !query.data?.length && <Card><Empty description={t('empty')} /></Card>}
      <Modal width={680} title={editing ? t('edit') : t('createScript')} open={modalOpen} onCancel={() => { setModalOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={mutation.isPending} destroyOnHidden>
        <Form<ScriptFormValues> form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('name')} name="name" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label={t('category')} name="category" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
          <Form.Item label={t('content')} name="content" rules={[{ required: true }]}><Input.TextArea rows={8} /></Form.Item>
          <Form.Item label={t('description')} name="description"><Input.TextArea rows={2} /></Form.Item>
          <Row gutter={12}><Col span={18}><Form.Item label={t('tags')} name="tags"><Input /></Form.Item></Col><Col span={6}><Form.Item label={t('enabled')} name="is_active" valuePropName="checked"><Switch /></Form.Item></Col></Row>
        </Form>
      </Modal>
    </>
  )
}

interface CampaignFormValues { name: string; script_template_id?: number; script?: string; mode: CallMode; concurrency: number; retry_limit: number; retry_interval_sec: number; attempt_interval_sec: number; contact_ids: number[]; recording_enabled: boolean; hangup_sms_enabled: boolean }

export function CampaignsPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<Campaign[]>(['campaigns'], '/api/v1/campaigns?page=1&size=100')
  const contacts = useSecureQuery<Contact[]>(['contacts', 'campaign-options'], '/api/v1/contacts?page=1&size=200')
  const scripts = useSecureQuery<ScriptTemplate[]>(['scripts', 'campaign-options'], '/api/v1/script-templates?active_only=true&page=1&size=200')
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm<CampaignFormValues>()
  const createMutation = useMutation({
    mutationFn: (values: CampaignFormValues) => apiRequest<Campaign>('/api/v1/campaigns', { method: 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['campaigns'] }) },
    onError: (error) => message.error(error.message),
  })
  const startMutation = useMutation({
    mutationFn: (id: number) => apiRequest(`/api/v1/campaigns/${id}/start?max_dials=10&async_dial=true`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['campaigns'] }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  return (
    <>
      <PageTitle title={t('campaigns')} description={t('campaignHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => { form.setFieldsValue({ mode: 'ai_handoff', concurrency: 5, retry_limit: 1, retry_interval_sec: 30, attempt_interval_sec: 1200, recording_enabled: true, hangup_sms_enabled: true, contact_ids: [] }); setModalOpen(true) }}>{t('createCampaign')}</Button>} />
      <Card>
        <Table<Campaign> rowKey="id" loading={query.isLoading} dataSource={(query.data || []).filter((item) => item.status !== 'deleted')} locale={{ emptyText: t('empty') }} scroll={{ x: 900 }} columns={[
          { title: 'ID', dataIndex: 'id', width: 70 },
          { title: t('name'), dataIndex: 'name', width: 200 },
          { title: t('mode'), dataIndex: 'mode', width: 150, render: (value) => t(modeOptions.find((item) => item.value === value)?.labelKey || value) },
          { title: t('contactsSelected'), dataIndex: 'contact_ids', render: (value: number[]) => value.length },
          { title: t('concurrency'), dataIndex: 'concurrency' },
          { title: t('status'), dataIndex: 'status', render: (value) => <StatusTag status={value} /> },
          { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
          { title: t('actions'), fixed: 'right', width: 120, render: (_, record) => <Popconfirm title={t('confirmStart')} onConfirm={() => startMutation.mutate(record.id)}><Button type="primary" ghost size="small" icon={<RocketOutlined />} loading={startMutation.isPending}>{t('start')}</Button></Popconfirm> },
        ]} />
      </Card>
      <Modal width={760} title={t('createCampaign')} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} confirmLoading={createMutation.isPending} destroyOnHidden>
        <Form<CampaignFormValues> form={form} layout="vertical" onFinish={(values) => createMutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('name')} name="name" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label={t('mode')} name="mode" rules={[{ required: true }]}><Select options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item></Col></Row>
          <Form.Item label={t('contactsSelected')} name="contact_ids" rules={[{ required: true }]}><Select mode="multiple" optionFilterProp="label" options={(contacts.data || []).map((item) => ({ value: item.id, label: `${item.name || '-'} · ${item.phone}` }))} /></Form.Item>
          <Form.Item label={t('scriptTemplate')} name="script_template_id"><Select allowClear options={(scripts.data || []).map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))} /></Form.Item>
          <Form.Item label={t('content')} name="script"><Input.TextArea rows={5} /></Form.Item>
          <Row gutter={12}><Col span={8}><Form.Item label={t('concurrency')} name="concurrency"><InputNumber min={1} max={1000} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('retryLimit')} name="retry_limit"><InputNumber min={1} max={10} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('retryInterval')} name="retry_interval_sec"><InputNumber min={1} className="full-width" /></Form.Item></Col></Row>
          <Form.Item name="attempt_interval_sec" hidden><InputNumber /></Form.Item>
          <Space size="large"><Form.Item label={t('recording')} name="recording_enabled" valuePropName="checked"><Switch /></Form.Item><Form.Item label={t('hangupSms')} name="hangup_sms_enabled" valuePropName="checked"><Switch /></Form.Item></Space>
        </Form>
      </Modal>
    </>
  )
}

interface CallFormValues { phone: string; mode: CallMode; campaign_id?: number; contact_id?: number; max_attempts: number }

function CallTable({ calls, loading, onAction, onEvents }: { calls: CallSession[]; loading: boolean; onAction: (call: CallSession, action: 'handover' | 'hangup' | 'retry') => void; onEvents: (call: CallSession) => void }) {
  const { t } = useTranslation()
  return <Table<CallSession> rowKey="id" loading={loading} dataSource={calls} locale={{ emptyText: t('empty') }} scroll={{ x: 1100 }} columns={[
    { title: t('phone'), dataIndex: 'phone', width: 150 },
    { title: t('mode'), dataIndex: 'mode', width: 150, render: (value) => t(modeOptions.find((item) => item.value === value)?.labelKey || value) },
    { title: t('status'), dataIndex: 'status', width: 130, render: (value) => <StatusTag status={value} /> },
    { title: t('attempts'), dataIndex: 'attempts', width: 100, render: (value, record) => `${value}/${record.max_attempts}` },
    { title: t('campaignId'), dataIndex: 'campaign_id', width: 110, render: (value) => value || '-' },
    { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
    { title: t('actions'), fixed: 'right', width: 280, render: (_, record) => <Space size={4}><Button size="small" onClick={() => onEvents(record)}>{t('events')}</Button><Button size="small" onClick={() => onAction(record, 'handover')}>{t('handoff')}</Button><Button size="small" danger onClick={() => onAction(record, 'hangup')}>{t('hangup')}</Button><Button size="small" onClick={() => onAction(record, 'retry')}>{t('retry')}</Button></Space> },
  ]} />
}

export function CallsPage({ role }: { role: Role }) {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [modalOpen, setModalOpen] = useState(false)
  const [selectedCall, setSelectedCall] = useState<CallSession | null>(null)
  const [form] = Form.useForm<CallFormValues>()
  const query = useSecureQuery<CallSession[]>(['calls', role], '/api/v1/calls?page=1&size=100')
  const events = useSecureQuery<CallEvent[]>(['call-events', selectedCall?.id || ''], selectedCall ? `/api/v1/calls/${selectedCall.id}/events?page=1&size=100` : '', Boolean(selectedCall))
  const createMutation = useMutation({
    mutationFn: (values: CallFormValues) => apiRequest<CallSession>('/api/v1/calls', { method: 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const actionMutation = useMutation({
    mutationFn: ({ call, action }: { call: CallSession; action: string }) => apiRequest<CallSession>(`/api/v1/calls/${call.id}/${action}`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const openCreate = () => { form.setFieldsValue({ mode: 'ai_handoff', max_attempts: 1 }); setModalOpen(true) }
  return (
    <>
      <PageTitle title={t('calls')} description={t('callHint')} action={<Space><Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button><Button type="primary" icon={<PhoneOutlined />} onClick={openCreate}>{t('startCall')}</Button></Space>} />
      <Card><CallTable calls={query.data || []} loading={query.isLoading} onEvents={setSelectedCall} onAction={(call, action) => actionMutation.mutate({ call, action })} /></Card>
      <Modal title={t('startCall')} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} confirmLoading={createMutation.isPending} destroyOnHidden>
        <Form<CallFormValues> form={form} layout="vertical" onFinish={(values) => createMutation.mutate(values)}>
          <Form.Item label={t('phone')} name="phone" rules={[{ required: true }]}><Input /></Form.Item>
          <Form.Item label={t('mode')} name="mode" rules={[{ required: true }]}><Select options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item>
          <Row gutter={12}><Col span={8}><Form.Item label={t('campaignId')} name="campaign_id"><InputNumber className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('contactId')} name="contact_id"><InputNumber className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('attempts')} name="max_attempts"><InputNumber min={1} max={10} className="full-width" /></Form.Item></Col></Row>
        </Form>
      </Modal>
      <Drawer width={620} title={`${t('events')} · ${selectedCall?.phone || ''}`} open={Boolean(selectedCall)} onClose={() => setSelectedCall(null)}>
        <Descriptions size="small" column={1} bordered items={selectedCall ? [{ key: 'id', label: 'ID', children: selectedCall.id }, { key: 'status', label: t('status'), children: <StatusTag status={selectedCall.status} /> }, { key: 'mode', label: t('mode'), children: t(modeOptions.find((item) => item.value === selectedCall.mode)?.labelKey || selectedCall.mode) }] : []} />
        <List className="event-list" loading={events.isLoading} dataSource={events.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag>{item.event_type}</Tag><Text type="secondary">{formatDate(item.created_at)}</Text></Space>} description={<pre>{item.payload}</pre>} /></List.Item>} />
      </Drawer>
    </>
  )
}

export function AgentWorkspacePage() {
  const { t } = useTranslation()
  const { token, user } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<CallSession[]>(['calls', 'agent-workspace'], '/api/v1/calls?page=1&size=20')
  const [form] = Form.useForm<CallFormValues>()
  const mutation = useMutation({
    mutationFn: (values: CallFormValues) => apiRequest<CallSession>('/api/v1/calls', { method: 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); form.resetFields(); form.setFieldsValue({ mode: 'ai_handoff', max_attempts: 1 }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const activeCalls = useMemo(() => (query.data || []).filter((item) => !['completed', 'failed', 'no_answer', 'busy'].includes(item.status)), [query.data])

  return (
    <>
      <PageTitle title={t('workspace')} description={t('workbenchHint')} />
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          <Card className="dialer-card" title={<Space><PhoneOutlined />{t('callNow')}</Space>}>
            <Form<CallFormValues> form={form} layout="vertical" initialValues={{ mode: 'ai_handoff', max_attempts: 1 }} onFinish={(values) => mutation.mutate(values)}>
              <Form.Item label={t('phone')} name="phone" rules={[{ required: true }]}><Input size="large" placeholder="13800000000" /></Form.Item>
              <Form.Item label={t('mode')} name="mode"><Select size="large" options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item>
              <Form.Item label={t('attempts')} name="max_attempts"><InputNumber min={1} max={10} size="large" className="full-width" /></Form.Item>
              <Button type="primary" size="large" block icon={<PhoneOutlined />} htmlType="submit" loading={mutation.isPending}>{t('callNow')}</Button>
            </Form>
          </Card>
          <Card className="agent-profile-card" title={t('profile')}>
            <Descriptions column={1} size="small" items={[{ key: 'name', label: t('contactName'), children: user?.full_name }, { key: 'id', label: t('username'), children: user?.username }, { key: 'status', label: t('agentStatus'), children: <BadgeStatus text={t('ready')} /> }]} />
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card title={t('activeQueue')} extra={<Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button>}>
            {activeCalls.length ? <List dataSource={activeCalls} renderItem={(call) => <List.Item actions={[<Button key="handoff" type="primary" ghost>{t('handoff')}</Button>]}><List.Item.Meta avatar={<div className="call-avatar"><PhoneOutlined /></div>} title={<Space>{call.phone}<StatusTag status={call.status} /></Space>} description={`${t('mode')}: ${t(modeOptions.find((item) => item.value === call.mode)?.labelKey || call.mode)} · ${formatDate(call.updated_at)}`} /></List.Item>} /> : <Empty description={t('empty')} />}
          </Card>
        </Col>
      </Row>
    </>
  )
}

function BadgeStatus({ text }: { text: string }) {
  return <Space><span className="status-dot" />{text}</Space>
}
