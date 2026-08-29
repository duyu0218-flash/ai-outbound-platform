import {
  ApiOutlined,
  AuditOutlined,
  CheckCircleOutlined,
  CloudServerOutlined,
  ControlOutlined,
  CustomerServiceOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  EditOutlined,
  FileTextOutlined,
  KeyOutlined,
  PhoneOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  RocketOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SettingOutlined,
  SoundOutlined,
  StopOutlined,
  TeamOutlined,
  UserAddOutlined,
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
  Tabs,
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
import type { AdminDashboard, AdminSetting, AdminUser, AuditLog, CallAnalysis, CallEvent, CallMetric, CallMode, CallSession, Campaign, Contact, RecordingAsset, Role, ScriptTemplate, SettingSection, SmsLog, SpeechTurn, SystemOverview, TelephonyLine, User } from './types'

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
    form.setFieldsValue(contact || { consent_state: 'unknown', dnc: false, timezone: 'Asia/Shanghai' })
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
  const remove = useMutation({
    mutationFn: (item: ScriptTemplate) => apiRequest(`/api/v1/script-templates/${item.id}`, { method: 'DELETE' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['scripts'] }) },
    onError: (error) => message.error(error.message),
  })
  const openEdit = (item?: ScriptTemplate) => { setEditing(item || null); form.setFieldsValue(item || { category: 'default', is_active: true }); setModalOpen(true) }
  return (
    <>
      <PageTitle title={t('scripts')} description={t('scriptHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>{t('createScript')}</Button>} />
      <Row gutter={[16, 16]}>{(query.data || []).map((item) => <Col xs={24} lg={12} xl={8} key={item.id}><Card loading={query.isLoading} className="script-card" title={<Space><FileTextOutlined /><span>{item.name}</span></Space>} extra={<Switch size="small" checked={item.is_active} onChange={() => toggle.mutate(item)} />} actions={[<Button type="text" icon={<EditOutlined />} onClick={() => openEdit(item)}>{t('edit')}</Button>, <Popconfirm title={t('confirmDelete')} onConfirm={() => remove.mutate(item)}><Button type="text" danger icon={<DeleteOutlined />}>{t('delete')}</Button></Popconfirm>]}><Space wrap><Tag>{item.category}</Tag><Tag>v{item.version}</Tag>{item.tags && <Tag color="blue">{item.tags}</Tag>}</Space><Paragraph ellipsis={{ rows: 4, expandable: true }} className="script-preview">{item.content}</Paragraph><Text type="secondary">{formatDate(item.updated_at)}</Text></Card></Col>)}</Row>
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
  const systemOverview = useSecureQuery<SystemOverview>(['system-overview'], '/api/v1/admin/system-overview')
  const effectiveCapacity = systemOverview.data?.capacity.effective_max_concurrent_calls
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Campaign | null>(null)
  const [form] = Form.useForm<CampaignFormValues>()
  const saveMutation = useMutation({
    mutationFn: (values: CampaignFormValues) => apiRequest<Campaign>(editing ? `/api/v1/campaigns/${editing.id}` : '/api/v1/campaigns', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); setEditing(null); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['campaigns'] }) },
    onError: (error) => message.error(error.message),
  })
  const startMutation = useMutation({
    mutationFn: (id: number) => apiRequest(`/api/v1/campaigns/${id}/start?async_dial=true`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['campaigns'] }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const statusMutation = useMutation({
    mutationFn: ({ id, action }: { id: number; action: 'pause' | 'resume' | 'stop' }) => apiRequest<Campaign>(`/api/v1/campaigns/${id}/${action}`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['campaigns'] }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const deleteMutation = useMutation({
    mutationFn: (id: number) => apiRequest(`/api/v1/campaigns/${id}`, { method: 'DELETE' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['campaigns'] }) },
    onError: (error) => message.error(error.message),
  })
  const openEdit = (campaign?: Campaign) => {
    setEditing(campaign || null)
    form.setFieldsValue(campaign || { mode: 'ai_handoff', concurrency: 5, retry_limit: 1, retry_interval_sec: 30, attempt_interval_sec: 1800, recording_enabled: true, hangup_sms_enabled: true, contact_ids: [] })
    setModalOpen(true)
  }
  return (
    <>
      <PageTitle title={t('campaigns')} description={t('campaignHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>{t('createCampaign')}</Button>} />
      <Card>
        <Table<Campaign> rowKey="id" loading={query.isLoading} dataSource={(query.data || []).filter((item) => item.status !== 'deleted')} locale={{ emptyText: t('empty') }} scroll={{ x: 900 }} columns={[
          { title: 'ID', dataIndex: 'id', width: 70 },
          { title: t('name'), dataIndex: 'name', width: 200 },
          { title: t('mode'), dataIndex: 'mode', width: 150, render: (value) => t(modeOptions.find((item) => item.value === value)?.labelKey || value) },
          { title: t('contactsSelected'), dataIndex: 'contact_ids', render: (value: number[]) => value.length },
          { title: t('concurrency'), dataIndex: 'concurrency' },
          { title: t('status'), dataIndex: 'status', render: (value) => <StatusTag status={value} /> },
          { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
          { title: t('actions'), fixed: 'right', width: 360, render: (_, record) => <Space size={4} wrap>
            {['draft', 'failed', 'stopped'].includes(record.status) && <Popconfirm title={t('confirmStart')} onConfirm={() => startMutation.mutate(record.id)}><Button type="primary" ghost size="small" icon={<RocketOutlined />} loading={startMutation.isPending}>{t('start')}</Button></Popconfirm>}
            {['draft', 'failed', 'stopped'].includes(record.status) && <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>{t('edit')}</Button>}
            {record.status === 'running' && <Button size="small" icon={<PauseCircleOutlined />} onClick={() => statusMutation.mutate({ id: record.id, action: 'pause' })}>{t('pause')}</Button>}
            {record.status === 'paused' && <Button size="small" icon={<PlayCircleOutlined />} onClick={() => statusMutation.mutate({ id: record.id, action: 'resume' })}>{t('resume')}</Button>}
            {['running', 'paused'].includes(record.status) && <Popconfirm title={t('confirmStop')} onConfirm={() => statusMutation.mutate({ id: record.id, action: 'stop' })}><Button size="small" danger icon={<StopOutlined />}>{t('stop')}</Button></Popconfirm>}
            {!['running', 'paused'].includes(record.status) && <Popconfirm title={t('confirmDelete')} onConfirm={() => deleteMutation.mutate(record.id)}><Button size="small" danger icon={<DeleteOutlined />}>{t('delete')}</Button></Popconfirm>}
          </Space> },
        ]} />
      </Card>
      <Modal width={760} title={editing ? t('editCampaign') : t('createCampaign')} open={modalOpen} onCancel={() => { setModalOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={saveMutation.isPending} destroyOnHidden>
        <Form<CampaignFormValues> form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('name')} name="name" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label={t('mode')} name="mode" rules={[{ required: true }]}><Select options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item></Col></Row>
          <Form.Item label={t('contactsSelected')} name="contact_ids" rules={[{ required: true }]}><Select mode="multiple" optionFilterProp="label" options={(contacts.data || []).map((item) => ({ value: item.id, label: `${item.name || '-'} · ${item.phone}` }))} /></Form.Item>
          <Form.Item label={t('scriptTemplate')} name="script_template_id"><Select allowClear options={(scripts.data || []).map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))} /></Form.Item>
          <Form.Item label={t('content')} name="script"><Input.TextArea rows={5} /></Form.Item>
          <Row gutter={12}><Col span={8}><Form.Item label={t('concurrency')} name="concurrency" extra={effectiveCapacity ? t('campaignConcurrencyHint', { count: effectiveCapacity }) : t('capacityLoading')}><InputNumber min={1} max={effectiveCapacity} disabled={!effectiveCapacity} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('retryLimit')} name="retry_limit"><InputNumber min={1} max={10} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('retryInterval')} name="retry_interval_sec"><InputNumber min={1} className="full-width" /></Form.Item></Col></Row>
          <Form.Item label={t('attemptInterval')} name="attempt_interval_sec"><InputNumber min={1} className="full-width" addonAfter={t('seconds')} /></Form.Item>
          <Space size="large"><Form.Item label={t('recording')} name="recording_enabled" valuePropName="checked"><Switch /></Form.Item><Form.Item label={t('hangupSms')} name="hangup_sms_enabled" valuePropName="checked"><Switch /></Form.Item></Space>
        </Form>
      </Modal>
    </>
  )
}

interface CallFormValues { phone: string; mode: CallMode; campaign_id?: number; contact_id?: number; max_attempts: number }

function CallTable({ calls, loading, onAction, onEvents }: { calls: CallSession[]; loading: boolean; onAction: (call: CallSession, action: 'handover' | 'hangup' | 'retry') => void; onEvents: (call: CallSession) => void }) {
  const { t } = useTranslation()
  const handoverable = (status: string) => ['dialing', 'answered', 'in_ai', 'waiting_human'].includes(status)
  const terminal = (status: string) => ['completed', 'failed', 'no_answer', 'busy', 'voicemail'].includes(status)
  return <Table<CallSession> rowKey="id" loading={loading} dataSource={calls} locale={{ emptyText: t('empty') }} scroll={{ x: 1100 }} columns={[
    { title: t('phone'), dataIndex: 'phone', width: 150 },
    { title: t('mode'), dataIndex: 'mode', width: 150, render: (value) => t(modeOptions.find((item) => item.value === value)?.labelKey || value) },
    { title: t('status'), dataIndex: 'status', width: 130, render: (value) => <StatusTag status={value} /> },
    { title: t('attempts'), dataIndex: 'attempts', width: 100, render: (value, record) => `${value}/${record.max_attempts}` },
    { title: t('campaignId'), dataIndex: 'campaign_id', width: 110, render: (value) => value || '-' },
    { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
    { title: t('actions'), fixed: 'right', width: 300, render: (_, record) => <Space size={4}><Button size="small" onClick={() => onEvents(record)}>{t('events')}</Button><Popconfirm title={t('confirmHandoff')} onConfirm={() => onAction(record, 'handover')} disabled={!handoverable(record.status)}><Button size="small" disabled={!handoverable(record.status)}>{t('handoff')}</Button></Popconfirm><Popconfirm title={t('confirmHangup')} onConfirm={() => onAction(record, 'hangup')} disabled={terminal(record.status)}><Button size="small" danger disabled={terminal(record.status)}>{t('hangup')}</Button></Popconfirm><Popconfirm title={t('confirmRetry')} onConfirm={() => onAction(record, 'retry')} disabled={!terminal(record.status) || record.attempts >= record.max_attempts}><Button size="small" disabled={!terminal(record.status) || record.attempts >= record.max_attempts}>{t('retry')}</Button></Popconfirm></Space> },
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
  const speechTurns = useSecureQuery<SpeechTurn[]>(['call-speech', selectedCall?.id || ''], selectedCall ? `/api/v1/calls/${selectedCall.id}/speech-turns` : '', Boolean(selectedCall))
  const metrics = useSecureQuery<CallMetric[]>(['call-metrics', selectedCall?.id || ''], selectedCall ? `/api/v1/calls/${selectedCall.id}/metrics` : '', Boolean(selectedCall))
  const recordings = useSecureQuery<RecordingAsset[]>(['call-recordings', selectedCall?.id || ''], selectedCall ? `/api/v1/calls/${selectedCall.id}/recordings` : '', Boolean(selectedCall))
  const analysis = useSecureQuery<CallAnalysis>(['call-analysis', selectedCall?.id || ''], selectedCall ? `/api/v1/calls/${selectedCall.id}/analysis` : '', Boolean(selectedCall))
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
          <Form.Item label={t('phone')} name="phone" rules={[{ required: true }, { pattern: /^\+?[0-9 ()-]{6,32}$/, message: t('invalidPhone') }]}><Input /></Form.Item>
          <Form.Item label={t('mode')} name="mode" rules={[{ required: true }]}><Select options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item>
          <Row gutter={12}><Col span={8}><Form.Item label={t('campaignId')} name="campaign_id"><InputNumber className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('contactId')} name="contact_id"><InputNumber className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('attempts')} name="max_attempts"><InputNumber min={1} max={10} className="full-width" /></Form.Item></Col></Row>
        </Form>
      </Modal>
      <Drawer width={720} title={`${t('events')} · ${selectedCall?.phone || ''}`} open={Boolean(selectedCall)} onClose={() => setSelectedCall(null)}>
        <Descriptions size="small" column={1} bordered items={selectedCall ? [{ key: 'id', label: 'ID', children: selectedCall.id }, { key: 'status', label: t('status'), children: <StatusTag status={selectedCall.status} /> }, { key: 'mode', label: t('mode'), children: t(modeOptions.find((item) => item.value === selectedCall.mode)?.labelKey || selectedCall.mode) }] : []} />
        <Tabs style={{ marginTop: 16 }} items={[
          { key: 'speech', label: '结构化转写', children: <List loading={speechTurns.isLoading} dataSource={speechTurns.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag color={item.is_final ? 'blue' : 'default'}>{item.speaker_role} · {item.is_final ? '最终' : '临时'}</Tag><Text type="secondary">置信度 {item.confidence == null ? '-' : `${Math.round(item.confidence * 100)}%`}</Text></Space>} description={item.transcript || '（空转写）'} /></List.Item>} /> },
          { key: 'analysis', label: '结果与质检', children: analysis.data ? <Descriptions bordered size="small" column={1} items={[{ key: 'result', label: '结果', children: analysis.data.result_code }, { key: 'intent', label: '意图', children: analysis.data.intent }, { key: 'sentiment', label: '情绪', children: analysis.data.sentiment }, { key: 'score', label: '质检分', children: analysis.data.qa_score }, { key: 'flags', label: '质检标记', children: analysis.data.qa_flags_json }, { key: 'summary', label: '摘要', children: analysis.data.summary }]} /> : <Empty description={analysis.isLoading ? '加载中' : t('empty')} /> },
          { key: 'recordings', label: '录音资产', children: <List loading={recordings.isLoading} dataSource={recordings.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag>{item.state}</Tag><Text>{item.media_format || 'unknown'}</Text><Text type="secondary">{item.duration_sec == null ? '-' : `${item.duration_sec}s`}</Text></Space>} description={item.storage_uri || item.provider_url} /></List.Item>} /> },
          { key: 'metrics', label: '阶段指标', children: <Table<CallMetric> size="small" rowKey="id" pagination={false} loading={metrics.isLoading} dataSource={metrics.data || []} columns={[{ title: '阶段', dataIndex: 'stage' }, { title: '耗时', dataIndex: 'duration_ms', render: (value) => value == null ? '-' : `${value} ms` }, { title: '结果', dataIndex: 'success', render: (value) => <Tag color={value ? 'success' : 'error'}>{value ? '成功' : '失败'}</Tag> }, { title: '错误码', dataIndex: 'error_code', render: (value) => value || '-' }]} /> },
          { key: 'events', label: t('events'), children: <List className="event-list" loading={events.isLoading} dataSource={events.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag>{item.event_type}</Tag><Text type="secondary">{formatDate(item.created_at)}</Text></Space>} description={<pre>{item.payload}</pre>} /></List.Item>} /> },
        ]} />
      </Drawer>
    </>
  )
}

interface AdminUserFormValues {
  username: string
  password?: string
  full_name: string
  phone?: string
  role: Role
  is_supervisor: boolean
  enabled: boolean
}

export function UsersPage() {
  const { t } = useTranslation()
  const { token, user: currentUser } = useAuth()
  const queryClient = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [searchKeyword, setSearchKeyword] = useState('')
  const [editing, setEditing] = useState<AdminUser | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm<AdminUserFormValues>()
  const query = useSecureQuery<AdminUser[]>(['admin-users', searchKeyword], `/api/v1/admin/users?page=1&size=200${searchKeyword ? `&keyword=${encodeURIComponent(searchKeyword)}` : ''}`)
  const saveMutation = useMutation({
    mutationFn: async (values: AdminUserFormValues) => {
      const { password, ...payload } = values
      const saved = await apiRequest<AdminUser>(editing ? `/api/v1/admin/users/${editing.id}` : '/api/v1/admin/users', {
        method: editing ? 'PUT' : 'POST',
        body: JSON.stringify(editing ? payload : { ...payload, password }),
      }, token)
      if (editing && password) {
        await apiRequest(`/api/v1/admin/users/${editing.id}/reset-password`, { method: 'POST', body: JSON.stringify({ password }) }, token)
      }
      return saved
    },
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); setEditing(null); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['admin-users'] }); void queryClient.invalidateQueries({ queryKey: ['audit-logs'] }) },
    onError: (error) => message.error(error.message),
  })
  const toggleMutation = useMutation({
    mutationFn: (item: AdminUser) => apiRequest(`/api/v1/admin/users/${item.id}`, { method: 'PUT', body: JSON.stringify({ enabled: !item.enabled }) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['admin-users'] }) },
    onError: (error) => message.error(error.message),
  })
  const openEdit = (item?: AdminUser) => {
    setEditing(item || null)
    form.setFieldsValue(item ? { ...item, password: undefined } : { role: 'agent', enabled: true, is_supervisor: false })
    setModalOpen(true)
  }
  return (
    <>
      <PageTitle title={t('users')} description={t('usersHint')} action={<Button type="primary" icon={<UserAddOutlined />} onClick={() => openEdit()}>{t('addUser')}</Button>} />
      <Card>
        <div className="table-toolbar">
          <Input allowClear value={keyword} prefix={<SearchOutlined />} placeholder={`${t('username')} / ${t('contactName')}`} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => setSearchKeyword(keyword)} />
          <Button type="primary" onClick={() => setSearchKeyword(keyword)}>{t('search')}</Button>
          <Button onClick={() => { setKeyword(''); setSearchKeyword('') }}>{t('reset')}</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button>
        </div>
        <Table<AdminUser> rowKey="id" loading={query.isLoading} dataSource={query.data || []} scroll={{ x: 980 }} columns={[
          { title: t('username'), dataIndex: 'username', width: 180 },
          { title: t('contactName'), dataIndex: 'full_name', width: 180 },
          { title: t('phone'), dataIndex: 'phone', width: 150, render: (value) => value || '-' },
          { title: t('role'), dataIndex: 'role', width: 110, render: (value) => <Tag color={value === 'admin' ? 'blue' : 'cyan'}>{value}</Tag> },
          { title: t('supervisor'), dataIndex: 'is_supervisor', width: 100, render: (value) => value ? t('yes') : t('no') },
          { title: t('enabled'), dataIndex: 'enabled', width: 100, render: (_, record) => <Switch checked={record.enabled} disabled={record.id === currentUser?.id} loading={toggleMutation.isPending} onChange={() => toggleMutation.mutate(record)} /> },
          { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
          { title: t('actions'), fixed: 'right', width: 100, render: (_, record) => <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(record)}>{t('edit')}</Button> },
        ]} />
      </Card>
      <Modal title={editing ? t('editUser') : t('addUser')} open={modalOpen} onCancel={() => { setModalOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={saveMutation.isPending} destroyOnHidden>
        <Form<AdminUserFormValues> form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('username')} name="username" rules={[{ required: true }]}><Input disabled={Boolean(editing)} /></Form.Item></Col><Col span={12}><Form.Item label={t('contactName')} name="full_name" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
          <Row gutter={12}><Col span={12}><Form.Item label={editing ? t('newPasswordOptional') : t('password')} name="password" rules={[{ required: !editing }, { min: 8 }]}><Input.Password autoComplete="new-password" /></Form.Item></Col><Col span={12}><Form.Item label={t('phone')} name="phone"><Input /></Form.Item></Col></Row>
          <Row gutter={12}><Col span={12}><Form.Item label={t('role')} name="role" rules={[{ required: true }]}><Select options={[{ value: 'admin', label: t('administrator') }, { value: 'agent', label: t('agent') }]} /></Form.Item></Col><Col span={6}><Form.Item label={t('supervisor')} name="is_supervisor" valuePropName="checked"><Switch /></Form.Item></Col><Col span={6}><Form.Item label={t('enabled')} name="enabled" valuePropName="checked"><Switch /></Form.Item></Col></Row>
        </Form>
      </Modal>
    </>
  )
}

interface LineFormValues {
  name: string
  provider: string
  gateway_url: string
  caller_id: string
  max_concurrency: number
  priority: number
  weight: number
  credential_ref: string
  enabled: boolean
}

export function LinesPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<TelephonyLine[]>(['admin-lines'], '/api/v1/admin/lines')
  const [editing, setEditing] = useState<TelephonyLine | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm<LineFormValues>()
  const saveMutation = useMutation({
    mutationFn: (values: LineFormValues) => apiRequest<TelephonyLine>(editing ? `/api/v1/admin/lines/${editing.id}` : '/api/v1/admin/lines', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setModalOpen(false); setEditing(null); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['admin-lines'] }); void queryClient.invalidateQueries({ queryKey: ['audit-logs'] }) },
    onError: (error) => message.error(error.message),
  })
  const toggleMutation = useMutation({
    mutationFn: (item: TelephonyLine) => apiRequest(`/api/v1/admin/lines/${item.id}`, { method: 'PUT', body: JSON.stringify({ enabled: !item.enabled }) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['admin-lines'] }) },
    onError: (error) => message.error(error.message),
  })
  const openEdit = (item?: TelephonyLine) => { setEditing(item || null); form.setFieldsValue(item || { provider: 'http', gateway_url: '', caller_id: '', max_concurrency: 10, priority: 100, weight: 1, credential_ref: '', enabled: true }); setModalOpen(true) }
  return (
    <>
      <PageTitle title={t('lines')} description={t('linesHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>{t('addLine')}</Button>} />
      <Card>
        <Table<TelephonyLine> rowKey="id" loading={query.isLoading} dataSource={query.data || []} scroll={{ x: 900 }} columns={[
          { title: t('name'), dataIndex: 'name', width: 180 },
          { title: t('provider'), dataIndex: 'provider', width: 130, render: (value) => <Tag>{value}</Tag> },
          { title: t('gatewayUrl'), dataIndex: 'gateway_url', ellipsis: true },
          { title: t('callerId'), dataIndex: 'caller_id', width: 150, render: (value) => value || '-' },
          { title: t('concurrency'), dataIndex: 'max_concurrency', width: 110 },
          { title: t('linePriority'), dataIndex: 'priority', width: 90 },
          { title: t('lineWeight'), dataIndex: 'weight', width: 80 },
          { title: t('enabled'), dataIndex: 'enabled', width: 100, render: (_, record) => <Switch checked={record.enabled} loading={toggleMutation.isPending} onChange={() => toggleMutation.mutate(record)} /> },
          { title: t('actions'), fixed: 'right', width: 100, render: (_, record) => <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(record)}>{t('edit')}</Button> },
        ]} />
      </Card>
      <Modal title={editing ? t('editLine') : t('addLine')} open={modalOpen} onCancel={() => { setModalOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={saveMutation.isPending} destroyOnHidden>
        <Form<LineFormValues> form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('name')} name="name" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label={t('provider')} name="provider" rules={[{ required: true }]}><Select options={[{ value: 'http', label: 'HTTP Bridge' }, { value: 'mock', label: t('mockTestOnly') }]} /></Form.Item></Col></Row>
          <Form.Item label={t('gatewayUrl')} name="gateway_url"><Input placeholder="https://voice-provider.example.com" /></Form.Item>
          <Form.Item label={t('credentialRef')} name="credential_ref" extra={t('credentialRefHint')}><Input placeholder="PRIMARY_PBX" /></Form.Item>
          <Row gutter={12}><Col span={8}><Form.Item label={t('callerId')} name="caller_id"><Input /></Form.Item></Col><Col span={6}><Form.Item label={t('concurrency')} name="max_concurrency"><InputNumber min={1} max={10000} className="full-width" /></Form.Item></Col><Col span={4}><Form.Item label={t('linePriority')} name="priority"><InputNumber min={1} max={10000} className="full-width" /></Form.Item></Col><Col span={4}><Form.Item label={t('lineWeight')} name="weight"><InputNumber min={1} max={100} className="full-width" /></Form.Item></Col><Col span={2}><Form.Item label={t('enabled')} name="enabled" valuePropName="checked"><Switch /></Form.Item></Col></Row>
        </Form>
      </Modal>
    </>
  )
}

function SettingSectionForm({ section }: { section: SettingSection }) {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [form] = Form.useForm<Record<string, string | number | boolean>>()
  const query = useSecureQuery<AdminSetting>(['admin-setting', section], `/api/v1/admin/settings/${section}`)
  const capacityOverview = useSecureQuery<SystemOverview>(['system-overview'], '/api/v1/admin/system-overview', section === 'capacity')
  useEffect(() => { if (query.data) form.setFieldsValue(query.data.data) }, [form, query.data])
  const mutation = useMutation({
    mutationFn: (values: Record<string, string | number | boolean>) => apiRequest<AdminSetting>(`/api/v1/admin/settings/${section}`, { method: 'PUT', body: JSON.stringify({ data: values }) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['admin-setting', section] }); void queryClient.invalidateQueries({ queryKey: ['system-overview'] }); void queryClient.invalidateQueries({ queryKey: ['audit-logs'] }) },
    onError: (error) => message.error(error.message),
  })
  const fields: Record<SettingSection, ReactNode> = {
    capacity: <><Alert type="warning" showIcon message={t('capacityChangeWarning')} description={t('capacityChangeDescription')} style={{ marginBottom: 16 }} /><Row gutter={16}><Col xs={24} lg={12}><Form.Item label={t('tenantCallCapacity')} name="max_concurrent_calls" rules={[{ required: true }]} extra={t('tenantCallCapacityHint')}><InputNumber min={1} max={10000} className="full-width" /></Form.Item></Col><Col xs={24} lg={12}><Card size="small" loading={capacityOverview.isLoading}><Descriptions column={1} size="small" items={[{ key: 'effective', label: t('effectiveCapacity'), children: capacityOverview.data?.capacity.effective_max_concurrent_calls ?? '-' }, { key: 'line', label: t('lineCapacity'), children: capacityOverview.data?.capacity.line_max_concurrency ?? t('notLimited') }, { key: 'active', label: t('activeCalls'), children: capacityOverview.data?.capacity.active_calls ?? '-' }, { key: 'available', label: t('availableSlots'), children: capacityOverview.data?.capacity.available_slots ?? '-' }, { key: 'source', label: t('limitingSource'), children: capacityOverview.data?.capacity.limiting_source ? t(capacityOverview.data.capacity.limiting_source) : '-' }]} /></Card></Col></Row></>,
    ai: <><Row gutter={16}><Col span={8}><Form.Item label={t('aiEnabled')} name="enabled" valuePropName="checked"><Switch /></Form.Item></Col><Col span={16}><Form.Item label={t('agentUrl')} name="agent_url" rules={[{ required: true }]}><Input /></Form.Item></Col></Row><Row gutter={16}><Col span={12}><Form.Item label={t('llmProvider')} name="llm_provider"><Select options={[{ value: 'rule', label: '规则模式（本地）' }, { value: 'openai-compatible', label: 'OpenAI-compatible' }]} /></Form.Item></Col><Col span={12}><Form.Item label={t('llmModel')} name="llm_model"><Input /></Form.Item></Col></Row><Row gutter={16}><Col span={8}><Form.Item label={t('asrProvider')} name="asr_provider"><Input /></Form.Item></Col><Col span={8}><Form.Item label={t('ttsProvider')} name="tts_provider"><Input /></Form.Item></Col><Col span={8}><Form.Item label={t('voice')} name="voice"><Input /></Form.Item></Col></Row><Form.Item label={t('language')} name="language"><Select options={[{ value: 'zh-CN', label: '中文' }, { value: 'en-US', label: 'English' }]} /></Form.Item></>,
    sms: <><Form.Item label={t('smsEnabled')} name="enabled" valuePropName="checked"><Switch /></Form.Item><Row gutter={16}><Col span={12}><Form.Item label={t('provider')} name="provider"><Select options={[{ value: 'mock', label: 'Mock（仅测试）' }, { value: 'http', label: 'HTTP Bridge' }]} /></Form.Item></Col><Col span={12}><Form.Item label={t('senderId')} name="sender_id"><Input /></Form.Item></Col></Row><Form.Item label={t('endpoint')} name="endpoint"><Input /></Form.Item><Form.Item label={t('hangupTemplate')} name="hangup_template"><Input.TextArea rows={4} /></Form.Item></>,
    compliance: <><Row gutter={16}><Col span={6}><Form.Item label={t('dncEnforced')} name="dnc_enforced" valuePropName="checked"><Switch /></Form.Item></Col><Col span={6}><Form.Item label={t('requireExplicitConsent')} name="require_explicit_consent" valuePropName="checked"><Switch /></Form.Item></Col><Col span={6}><Form.Item label={t('recordingNotice')} name="recording_notice" valuePropName="checked"><Switch /></Form.Item></Col><Col span={6}><Form.Item label={t('maxAttemptsDay')} name="max_attempts_per_day"><InputNumber min={1} max={20} className="full-width" /></Form.Item></Col></Row><Row gutter={16}><Col span={8}><Form.Item label={t('startHour')} name="allowed_start_hour"><InputNumber min={0} max={23} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('endHour')} name="allowed_end_hour"><InputNumber min={0} max={23} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('timezone')} name="timezone"><Input /></Form.Item></Col></Row></>,
    integration: <><Form.Item label={t('callbackEnabled')} name="callback_enabled" valuePropName="checked"><Switch /></Form.Item><Form.Item label={t('webhookBaseUrl')} name="webhook_base_url"><Input /></Form.Item><Form.Item label={t('webhookSecretRef')} name="webhook_secret_ref" extra={t('webhookSecretHint')}><Input placeholder="PRIMARY_CALLBACK" /></Form.Item><Row gutter={16}><Col span={8}><Form.Item label={t('webhookTimeout')} name="webhook_timeout_sec"><InputNumber min={1} max={120} addonAfter={t('seconds')} /></Form.Item></Col><Col span={8}><Form.Item label={t('webhookRetryTimes')} name="webhook_retry_times"><InputNumber min={0} max={10} /></Form.Item></Col><Col span={8}><Form.Item label={t('webhookRetryBackoff')} name="webhook_retry_backoff_sec"><InputNumber min={1} max={60} addonAfter={t('seconds')} /></Form.Item></Col></Row></>,
  }
  return <Card loading={query.isLoading} className="settings-card"><Form form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)}>{fields[section]}<Space><Button type="primary" htmlType="submit" loading={mutation.isPending}>{t('save')}</Button>{query.data?.updated_at && <Text type="secondary">{t('lastUpdated')}: {formatDate(query.data.updated_at)}</Text>}</Space></Form></Card>
}

export function SettingsPage() {
  const { t } = useTranslation()
  return (
    <>
      <PageTitle title={t('settings')} description={t('settingsHint')} />
      <Alert type="info" showIcon message={t('settingsSecurityHint')} style={{ marginBottom: 16 }} />
      <Tabs items={[
        { key: 'capacity', label: <Space><CloudServerOutlined />{t('capacitySettings')}</Space>, children: <SettingSectionForm section="capacity" /> },
        { key: 'ai', label: <Space><ControlOutlined />{t('aiVoice')}</Space>, children: <SettingSectionForm section="ai" /> },
        { key: 'sms', label: <Space><SoundOutlined />{t('smsSettings')}</Space>, children: <SettingSectionForm section="sms" /> },
        { key: 'compliance', label: <Space><SafetyCertificateOutlined />{t('compliance')}</Space>, children: <SettingSectionForm section="compliance" /> },
        { key: 'integration', label: <Space><ApiOutlined />{t('integrations')}</Space>, children: <SettingSectionForm section="integration" /> },
      ]} />
    </>
  )
}

export function SystemPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const overview = useSecureQuery<SystemOverview>(['system-overview'], '/api/v1/admin/system-overview')
  const audits = useSecureQuery<AuditLog[]>(['audit-logs'], '/api/v1/admin/audit-logs?page=1&size=100')
  const smsLogs = useSecureQuery<SmsLog[]>(['sms-logs'], '/api/v1/admin/sms-logs?page=1&size=100')
  const retrySms = useMutation({
    mutationFn: (item: SmsLog) => apiRequest<SmsLog>(`/api/v1/admin/sms-logs/${item.id}/retry`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['sms-logs'] }); void queryClient.invalidateQueries({ queryKey: ['audit-logs'] }) },
    onError: (error) => message.error(error.message),
  })
  const serviceLabels: Record<string, string> = { database: t('database'), redis: 'Redis', ai_agent: t('aiService'), telephony: t('telephony') }
  return (
    <>
      <PageTitle title={t('system')} description={t('systemHint')} action={<Button icon={<ReloadOutlined />} onClick={() => { void overview.refetch(); void audits.refetch() }}>{t('refresh')}</Button>} />
      <Row gutter={[16, 16]}>
        <Col xs={24} lg={10}><Card title={<Space><CloudServerOutlined />{t('serviceHealth')}</Space>} loading={overview.isLoading}><Descriptions column={1} bordered size="small" items={Object.entries(overview.data?.services || {}).map(([key, value]) => ({ key, label: serviceLabels[key] || key, children: <Tag color={value === 'ok' ? 'success' : ['unconfigured', 'mock'].includes(value) ? 'warning' : 'error'}>{value}</Tag> }))} /></Card></Col>
        <Col xs={24} lg={14}><Row gutter={[16, 16]}><Col span={12}><Card><Statistic title={t('enabledUsers')} value={overview.data?.resources.enabled_users || 0} suffix={`/ ${overview.data?.resources.users || 0}`} prefix={<TeamOutlined />} /></Card></Col><Col span={12}><Card><Statistic title={t('enabledLines')} value={overview.data?.resources.enabled_lines || 0} suffix={`/ ${overview.data?.resources.lines || 0}`} prefix={<PhoneOutlined />} /></Card></Col><Col span={24}><Card title={t('callStatusDistribution')}><Space wrap>{Object.entries(overview.data?.call_statuses || {}).map(([key, value]) => <Tag key={key} color="blue">{t(key, { defaultValue: key })}: {value}</Tag>)}{!Object.keys(overview.data?.call_statuses || {}).length && <Text type="secondary">{t('empty')}</Text>}</Space></Card></Col></Row></Col>
      </Row>
      <Card title={<Space><ControlOutlined />{t('capacityOverview')}</Space>} className="audit-card" loading={overview.isLoading}><Row gutter={[16, 16]}><Col xs={12} lg={6}><Statistic title={t('configuredCapacity')} value={overview.data?.capacity.configured_max_concurrent_calls || 0} /></Col><Col xs={12} lg={6}><Statistic title={t('effectiveCapacity')} value={overview.data?.capacity.effective_max_concurrent_calls || 0} /></Col><Col xs={12} lg={6}><Statistic title={t('activeCalls')} value={overview.data?.capacity.active_calls || 0} /></Col><Col xs={12} lg={6}><Statistic title={t('availableSlots')} value={overview.data?.capacity.available_slots || 0} /></Col></Row></Card>
      <Card title={<Space><AuditOutlined />{t('auditLogs')}</Space>} className="audit-card"><Table<AuditLog> rowKey="id" loading={audits.isLoading} dataSource={audits.data || []} pagination={{ pageSize: 12 }} scroll={{ x: 900 }} columns={[
        { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
        { title: t('operator'), dataIndex: 'actor_username', width: 150 },
        { title: t('action'), dataIndex: 'action', width: 130, render: (value) => <Tag>{value}</Tag> },
        { title: t('resource'), dataIndex: 'resource_type', width: 150 },
        { title: t('resourceId'), dataIndex: 'resource_id', width: 120, render: (value) => value || '-' },
        { title: t('details'), dataIndex: 'detail', ellipsis: true },
      ]} /></Card>
      <Card title={<Space><SoundOutlined />{t('smsLogs')}</Space>} className="audit-card"><Table<SmsLog> rowKey="id" loading={smsLogs.isLoading} dataSource={smsLogs.data || []} pagination={{ pageSize: 12 }} scroll={{ x: 900 }} columns={[
        { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
        { title: t('phone'), dataIndex: 'to_phone', width: 150 },
        { title: t('status'), dataIndex: 'state', width: 120, render: (value) => <Tag color={String(value).includes('failed') ? 'error' : 'success'}>{value}</Tag> },
        { title: t('providerMessageId'), dataIndex: 'provider_message_id', width: 190, ellipsis: true, render: (value) => value || '-' },
        { title: t('providerError'), dataIndex: 'provider_error', width: 180, ellipsis: true, render: (value) => value || '-' },
        { title: t('content'), dataIndex: 'content', ellipsis: true },
        { title: t('sentAt'), dataIndex: 'sent_at', width: 170, render: formatDate },
        { title: t('actions'), fixed: 'right', width: 100, render: (_, record) => <Button size="small" disabled={!['failed', 'disabled'].includes(record.state)} loading={retrySms.isPending} onClick={() => retrySms.mutate(record)}>{t('retry')}</Button> },
      ]} /></Card>
    </>
  )
}

export function AgentWorkspacePage() {
  const { t } = useTranslation()
  const { token, user } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<CallSession[]>(['calls', 'agent-workspace'], '/api/v1/calls?page=1&size=20')
  const [form] = Form.useForm<CallFormValues>()
  const [presenceStatus, setPresenceStatus] = useState<'ready' | 'busy' | 'offline'>(user?.agent_status || 'ready')
  useEffect(() => {
    if (!token || !user || user.role !== 'agent') return
    const updatePresence = () => apiRequest<User>('/api/v1/auth/presence', { method: 'PUT', body: JSON.stringify({ status: presenceStatus }) }, token).catch(() => undefined)
    void updatePresence()
    const heartbeat = window.setInterval(() => void updatePresence(), 30_000)
    return () => window.clearInterval(heartbeat)
  }, [presenceStatus, token, user])
  const mutation = useMutation({
    mutationFn: (values: CallFormValues) => apiRequest<CallSession>('/api/v1/calls', { method: 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); form.resetFields(); form.setFieldsValue({ mode: 'ai_handoff', max_attempts: 1 }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const handoffMutation = useMutation({
    mutationFn: (call: CallSession) => apiRequest<CallSession>(`/api/v1/calls/${call.id}/handover?reason=agent_workspace`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setPresenceStatus('busy'); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const activeCalls = useMemo(() => (query.data || []).filter((item) => !['completed', 'failed', 'no_answer', 'busy', 'voicemail'].includes(item.status)), [query.data])

  return (
    <>
      <PageTitle title={t('workspace')} description={t('workbenchHint')} />
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          <Card className="dialer-card" title={<Space><PhoneOutlined />{t('callNow')}</Space>}>
            <Form<CallFormValues> form={form} layout="vertical" initialValues={{ mode: 'ai_handoff', max_attempts: 1 }} onFinish={(values) => mutation.mutate(values)}>
              <Form.Item label={t('phone')} name="phone" rules={[{ required: true }, { pattern: /^\+?[0-9 ()-]{6,32}$/, message: t('invalidPhone') }]}><Input size="large" placeholder="13800000000" /></Form.Item>
              <Form.Item label={t('mode')} name="mode"><Select size="large" options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item>
              <Form.Item label={t('attempts')} name="max_attempts"><InputNumber min={1} max={10} size="large" className="full-width" /></Form.Item>
              <Button type="primary" size="large" block icon={<PhoneOutlined />} htmlType="submit" loading={mutation.isPending}>{t('callNow')}</Button>
            </Form>
          </Card>
          <Card className="agent-profile-card" title={t('profile')}>
            <Descriptions column={1} size="small" items={[{ key: 'name', label: t('contactName'), children: user?.full_name }, { key: 'id', label: t('username'), children: user?.username }, { key: 'status', label: t('agentStatus'), children: <Select value={presenceStatus} style={{ width: 140 }} onChange={setPresenceStatus} options={[{ value: 'ready', label: t('agentReady') }, { value: 'busy', label: t('agentBusy') }, { value: 'offline', label: t('agentOffline') }]} /> }]} />
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card title={t('activeQueue')} extra={<Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button>}>
            {activeCalls.length ? <List dataSource={activeCalls} renderItem={(call) => <List.Item actions={[<Button key="handoff" type="primary" ghost loading={handoffMutation.isPending} onClick={() => handoffMutation.mutate(call)} disabled={presenceStatus !== 'ready' || !['dialing', 'answered', 'in_ai', 'waiting_human'].includes(call.status)}>{t('handoff')}</Button>]}><List.Item.Meta avatar={<div className="call-avatar"><PhoneOutlined /></div>} title={<Space>{call.phone}<StatusTag status={call.status} /></Space>} description={`${t('mode')}: ${t(modeOptions.find((item) => item.value === call.mode)?.labelKey || call.mode)} · ${formatDate(call.updated_at)}`} /></List.Item>} /> : <Empty description={t('empty')} />}
          </Card>
        </Col>
      </Row>
    </>
  )
}
