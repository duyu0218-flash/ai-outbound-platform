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
  DollarOutlined,
  FileTextOutlined,
  LineChartOutlined,
  DownloadOutlined,
  UploadOutlined,
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
  Spin,
  Statistic,
  Switch,
  Tabs,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useNavigate } from 'react-router-dom'
import { ApiError, apiRequest, formatDate } from './api'
import { useAuth } from './auth'
import { setLanguage } from './i18n'
import { WebRtcSoftphone } from './webrtc'
import type {
  AdminBillingPayload,
  AdminCallReportPayload,
  AdminContactGroupPayload,
  ContactBatchDncResult,
  ContactImportResult,
  AdminDashboard,
  AdminSetting,
  AdminUser,
  AuditLog,
  CallAnalysis,
  CallEvent,
  CallMetric,
  CallMode,
  CallSession,
  Campaign,
  Contact,
  FlowEdge,
  FlowNode,
  FlowNodeType,
  HandoffRequest,
  KnowledgeItem,
  QualityReviewItem,
  RecordingAsset,
  Role,
  RuntimeInfo,
  ScriptFlowVersion,
  ScriptTemplate,
  SettingSection,
  SmsLog,
  SpeechTurn,
  SystemOverview,
  TelephonyLine,
  User,
} from './types'

const { Title, Text, Paragraph } = Typography

const isFutureUtc = (value?: string) => Boolean(
  value && new Date(value.endsWith('Z') ? value : `${value}Z`).getTime() > Date.now(),
)

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

const reportDimensionOptions = [
  { value: 'campaign', labelKey: 'campaign' },
  { value: 'agent', labelKey: 'agent' },
  { value: 'line', labelKey: 'line' },
] as const

const reportGranularityOptions = [
  { value: 'day', labelKey: 'day' },
  { value: 'hour', labelKey: 'hour' },
] as const

function StatusTag({ status }: { status: string }) {
  const { t } = useTranslation()
  const colors: Record<string, string> = { running: 'processing', completed: 'success', failed: 'error', draft: 'default', answered: 'success', in_ai: 'blue', waiting_human: 'orange', dialing: 'processing', queued: 'cyan' }
  return <Tag color={colors[status] || 'default'}>{t(status, { defaultValue: status })}</Tag>
}

function formatRateLabel(numerator: number, denominator: number, emptyText: string): string {
  if (denominator <= 0) {
    return emptyText
  }
  return `${Math.round((numerator / denominator) * 100)}%`
}

export function LoginPage({ role }: { role: Role }) {
  const { t, i18n } = useTranslation()
  const { login, user } = useAuth()
  const navigate = useNavigate()
  const [submitting, setSubmitting] = useState(false)
  const runtime = useQuery({ queryKey: ['runtime'], queryFn: () => apiRequest<RuntimeInfo>('/api/v1/runtime') })
  const demoEnabled = runtime.data?.demo_users_enabled === true

  if (user) return <Navigate to={user.role === 'admin' && role === 'admin' ? '/admin' : '/agent'} replace />
  if (runtime.isLoading) return <div className="app-loading"><Spin size="large" /></div>

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
        <Form layout="vertical" size="large" onFinish={submit}>
          <Form.Item label={t('username')} name="username" rules={[{ required: true }]}><Input autoComplete="username" /></Form.Item>
          <Form.Item label={t('password')} name="password" rules={[{ required: true }]}><Input.Password autoComplete="current-password" /></Form.Item>
          <Button type="primary" htmlType="submit" block loading={submitting}>{t('signIn')}</Button>
        </Form>
        {demoEnabled && <Alert className="demo-account" type="info" showIcon message={role === 'admin' ? t('adminDemo') : t('agentDemo')} />}
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
  const [days, setDays] = useState(30)
  const dashboard = useSecureQuery<AdminDashboard>(['admin-dashboard', String(days)], `/api/v1/admin/dashboard?days=${days}`)
  const calls = useSecureQuery<CallSession[]>(['calls', 'dashboard'], '/api/v1/calls?page=1&size=200')
  const loading = dashboard.isLoading || calls.isLoading
  const periodSummary = dashboard.data?.period
  const reachedRate = periodSummary ? formatRateLabel(periodSummary.reached, periodSummary.calls, t('empty')) : t('empty')
  const interestedRate = periodSummary ? formatRateLabel(periodSummary.interested, periodSummary.reached, t('empty')) : t('empty')
  const stats = [
    { title: t('periodCalls'), value: dashboard.data?.period.calls || 0, icon: <PhoneOutlined />, color: 'blue' },
    { title: t('reachedCalls'), value: dashboard.data?.period.reached || 0, suffix: reachedRate, icon: <CustomerServiceOutlined />, color: 'green' },
    { title: t('interestedLeads'), value: dashboard.data?.period.interested || 0, suffix: interestedRate, icon: <RocketOutlined />, color: 'orange' },
    { title: t('averageQaScore'), value: dashboard.data?.period.average_qa_score || 0, suffix: '/ 100', icon: <AuditOutlined />, color: 'purple' },
  ]
  return (
    <>
      <PageTitle title={t('overview')} description={t('overviewHint')} action={<Select value={days} onChange={setDays} options={[{ value: 7, label: t('last7Days') }, { value: 30, label: t('last30Days') }, { value: 90, label: t('last90Days') }]} />} />
      {dashboard.isError && <Alert type="error" showIcon message={t('loadFailed')} description={dashboard.error.message} style={{ marginBottom: 16 }} />}
      <Row gutter={[16, 16]}>
        {stats.map((item) => <Col xs={24} sm={12} xl={6} key={item.title}><Card loading={loading} className="metric-card"><div className={`metric-icon ${item.color}`}>{item.icon}</div><Statistic title={item.title} value={item.value} suffix={item.suffix} /></Card></Col>)}
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
      <Card title={t('campaignPerformance')} className="audit-card" extra={<Text type="secondary">{t('metricScopeHint', { days })}</Text>}>
        <Table rowKey="campaign_id" loading={dashboard.isLoading} dataSource={dashboard.data?.campaign_performance || []} pagination={false} locale={{ emptyText: t('empty') }} columns={[
          { title: t('campaignId'), dataIndex: 'campaign_id', width: 100 },
          { title: t('name'), dataIndex: 'name' },
          { title: t('periodCalls'), dataIndex: 'calls', width: 120 },
          { title: t('reachedCalls'), dataIndex: 'reached', width: 120 },
          { title: t('reachRate'), dataIndex: 'reach_rate', width: 120, render: (value) => `${value}%` },
          { title: t('interestedLeads'), dataIndex: 'interested', width: 120 },
          { title: t('interestRate'), dataIndex: 'interest_rate', width: 120, render: (value) => `${value}%` },
        ]} />
        <Text type="secondary">{dashboard.data?.metric_definitions.reached}；{dashboard.data?.metric_definitions.interested}</Text>
      </Card>
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

interface ContactBatchForm {
  dnc: boolean
  dnc_reason: string
}

function parseNumberList(raw: string): number[] {
  return raw
    .split(/[,\s\r\n]+/)
    .map((item) => item.trim())
    .filter(Boolean)
    .map((item) => Number(item))
    .filter((item) => Number.isInteger(item) && item > 0)
}

export function ContactOperationsPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [keyword, setKeyword] = useState('')
  const [searchKeyword, setSearchKeyword] = useState('')
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([])
  const [dncOpen, setDncOpen] = useState(false)
  const [batchForm] = Form.useForm<ContactBatchForm>()
  const [importUpsert, setImportUpsert] = useState(true)
  const [importResult, setImportResult] = useState<ContactImportResult | null>(null)
  const [selectedIdsText, setSelectedIdsText] = useState('')
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const importRequestKeys = useRef(new Map<string, string>())
  const query = useSecureQuery<Contact[]>(['admin-contacts-ops', searchKeyword], `/api/v1/contacts?page=1&size=200${searchKeyword ? `&keyword=${encodeURIComponent(searchKeyword)}` : ''}`)

  const importMutation = useMutation({
    mutationFn: ({ file, upsert }: { file: File; upsert: boolean }) => {
      const form = new FormData()
      form.append('file', file)
      const fingerprint = `${file.name}:${file.size}:${file.lastModified}:${upsert}`
      const requestKey = importRequestKeys.current.get(fingerprint) || crypto.randomUUID()
      importRequestKeys.current.set(fingerprint, requestKey)
      return apiRequest<ContactImportResult>(`/api/v1/contacts/import?upsert=${upsert}`, {
        method: 'POST',
        body: form,
        headers: { 'Idempotency-Key': requestKey },
        timeoutMs: 10 * 60_000,
      }, token)
    },
    onSuccess: (result, variables) => {
      importRequestKeys.current.delete(`${variables.file.name}:${variables.file.size}:${variables.file.lastModified}:${variables.upsert}`)
      setImportResult(result)
      void queryClient.invalidateQueries({ queryKey: ['admin-contacts-ops'] })
      message.success(t('operationSuccess'))
    },
    onError: (error) => message.error(error.message),
  })

  const batchMutation = useMutation({
    mutationFn: (payload: { contact_ids: number[]; dnc: boolean; dnc_reason: string }) => {
      const ids = payload.contact_ids
      if (!ids.length) {
        throw new ApiError(t('noSelectedContacts'), 400)
      }
      return apiRequest<ContactBatchDncResult>('/api/v1/contacts/batch-dnc', {
        method: 'PATCH',
        body: JSON.stringify({ ...payload, contact_ids: ids }),
      }, token)
    },
    onSuccess: (result) => {
      message.success(`${t('operationSuccess')}: ${result.updated}`)
      setDncOpen(false)
      setSelectedRowKeys([])
      batchForm.resetFields()
      setSelectedIdsText('')
      void queryClient.invalidateQueries({ queryKey: ['admin-contacts-ops'] })
    },
    onError: (error) => message.error(error.message),
  })

  const handleImportFile = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file) return
    importMutation.mutate({ file, upsert: importUpsert })
    event.target.value = ''
  }

  const triggerExport = async () => {
    if (!token) return
    try {
      const response = await fetch('/api/v1/contacts/export', { headers: { Authorization: `Bearer ${token}` } })
      if (!response.ok) {
        const body = await response.json().catch(() => ({} as { message?: string }))
        throw new Error((body as any).message || `HTTP ${response.status}`)
      }
      const blob = await response.blob()
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = `contacts-${Date.now()}.csv`
      link.click()
      URL.revokeObjectURL(link.href)
      message.success(t('operationSuccess'))
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('loadFailed'))
    }
  }

  const openBatch = () => {
    const selectedIds = selectedRowKeys.map((value) => Number(value))
    if (!selectedIds.length) return
    setSelectedIdsText(selectedIds.join(','))
    setDncOpen(true)
    batchForm.setFieldsValue({ dnc: true, dnc_reason: '' })
  }

  const submitBatch = () => {
    const ids = parseNumberList(selectedIdsText)
    const values = batchForm.getFieldsValue()
    batchMutation.mutate({
      contact_ids: ids,
      dnc: Boolean(values.dnc),
      dnc_reason: values.dnc_reason?.trim() || '',
    })
  }

  const selection = useMemo(
    () => ({
      selectedRowKeys,
      onChange: (keys: React.Key[]) => setSelectedRowKeys(keys),
    }),
    [selectedRowKeys],
  )

  return (
    <>
      <PageTitle
        title={t('contactsOperations')}
        description={t('contactsOperationsHint')}
        action={
          <Space>
            <Button icon={<UploadOutlined />} onClick={() => fileInputRef.current?.click()} loading={importMutation.isPending}>{t('import')}</Button>
            <input ref={fileInputRef} type="file" accept=".csv,text/csv" style={{ display: 'none' }} onChange={handleImportFile} />
            <Button icon={<DownloadOutlined />} onClick={triggerExport}>{t('export')}</Button>
            <Button onClick={() => setImportResult(null)}>{t('clearResult')}</Button>
          </Space>
        }
      />
      <Card>
        <div className="table-toolbar">
          <Input allowClear value={keyword} prefix={<SearchOutlined />} placeholder={`${t('phone')} / ${t('name')}`} onChange={(event) => setKeyword(event.target.value)} onPressEnter={() => setSearchKeyword(keyword)} />
          <Button type="primary" onClick={() => setSearchKeyword(keyword)}>{t('search')}</Button>
          <Button onClick={() => { setKeyword(''); setSearchKeyword('') }}>{t('reset')}</Button>
          <span>{t('upsert')}</span>
          <Switch checked={importUpsert} onChange={setImportUpsert} />
          <Button danger disabled={!selectedRowKeys.length} onClick={openBatch}>{t('batchDnc')}</Button>
        </div>
        <Table<Contact>
          rowKey="id"
          loading={query.isLoading}
          dataSource={query.data || []}
          locale={{ emptyText: t('empty') }}
          rowSelection={selection}
          scroll={{ x: 900 }}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 70 },
            { title: t('phone'), dataIndex: 'phone', width: 160 },
            { title: t('contactName'), dataIndex: 'name', width: 150, render: (value) => value || '-' },
            { title: t('tags'), dataIndex: 'tags', render: (value) => value ? <Tag>{value}</Tag> : '-' },
            { title: t('dncReason'), dataIndex: 'dnc_reason', render: (value) => value || '-' },
            { title: t('dnc'), dataIndex: 'dnc', width: 90, render: (value) => value ? <Tag color="red">{t('yes')}</Tag> : <Tag color="green">{t('no')}</Tag> },
            { title: t('consent'), dataIndex: 'consent_state', render: (value) => <StatusTag status={value} /> },
            { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
          ]}
        />
      </Card>
      {importResult && <Card style={{ marginTop: 16 }}>
        <Descriptions title={t('importResult')} column={3} bordered size="small" items={[
          { key: 'total', label: t('total'), children: importResult.total },
          { key: 'created', label: t('created'), children: importResult.created },
          { key: 'updated', label: t('updated'), children: importResult.updated },
          { key: 'skipped', label: t('skipped'), children: importResult.skipped },
          { key: 'failed', label: t('failed'), children: importResult.failed },
          { key: 'errors', label: t('errors'), children: `${importResult.errors.length} ${t('items')}` },
        ]} />
        {importResult.errors.length > 0 && <Card><List size="small" header={<Text type="secondary">{t('errors')}</Text>} dataSource={importResult.errors} renderItem={(item) => <List.Item>{item}</List.Item>} /></Card>}
      </Card>}
      <Modal open={dncOpen} title={t('batchDnc')} onCancel={() => setDncOpen(false)} onOk={submitBatch} confirmLoading={batchMutation.isPending}>
        <Form form={batchForm} layout="vertical" initialValues={{ dnc: true, dnc_reason: '' }}>
          <Form.Item label={t('selectedContactIds')}><Input.TextArea value={selectedIdsText} readOnly rows={2} /></Form.Item>
          <Form.Item name="dnc" label={t('dnc')} valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="dnc_reason" label={t('dncReason')}><Input.TextArea rows={2} placeholder={t('optional')} /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}

interface ScriptFormValues { name: string; content: string; category: string; description?: string; tags?: string; is_active: boolean }

export function ReportPage() {
  const { t } = useTranslation()
  const [dimension, setDimension] = useState<'campaign' | 'agent' | 'line'>('campaign')
  const [granularity, setGranularity] = useState<'day' | 'hour'>('day')
  const [days, setDays] = useState(30)
  const report = useSecureQuery<AdminCallReportPayload>(['admin-call-reports', dimension, granularity, String(days)], `/api/v1/admin/call-reports?dimension=${dimension}&granularity=${granularity}&days=${days}`)

  return (
    <>
      <PageTitle
        title={t('callReports')}
        description={t('callReportsHint')}
        action={
          <Space>
            <Select value={dimension} options={reportDimensionOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} onChange={setDimension} />
            <Select value={granularity} options={reportGranularityOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} onChange={setGranularity} />
            <Select value={days} onChange={setDays} options={[{ value: 7, label: t('last7Days') }, { value: 30, label: t('last30Days') }, { value: 90, label: t('last90Days') }]} />
            <Button onClick={() => void report.refetch()} icon={<ReloadOutlined />}>{t('refresh')}</Button>
          </Space>
        }
      />
      {report.isError && <Alert style={{ marginBottom: 16 }} showIcon type="error" message={t('loadFailed')} description={report.error.message} />}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}><Card loading={report.isLoading}><Statistic title={t('total')} value={report.data?.summary?.calls || 0} /></Card></Col>
        <Col xs={24} sm={8}><Card loading={report.isLoading}><Statistic title={t('reachedCalls')} value={report.data?.summary?.reached || 0} suffix={`(${report.data?.summary ? formatRateLabel(report.data.summary.reached, report.data.summary.calls, t('empty')) : t('empty')})`} /></Card></Col>
        <Col xs={24} sm={8}><Card loading={report.isLoading}><Statistic title={t('handoff')} value={report.data?.summary?.handoff || 0} /></Card></Col>
      </Row>
      <Card style={{ marginTop: 16 }} title={t('callReportRows')} loading={report.isLoading}>
        <Table rowKey="key" pagination={{ pageSize: 10 }} dataSource={report.data?.rows || []} locale={{ emptyText: t('empty') }} columns={[
          { title: t('dimension'), dataIndex: 'label', width: 220 },
          { title: t('periodCalls'), dataIndex: 'calls' },
          { title: t('reachedCalls'), dataIndex: 'reached' },
          { title: t('handoff'), dataIndex: 'handoff' },
          { title: t('completed'), dataIndex: 'completed' },
          { title: t('failed'), dataIndex: 'failed' },
          { title: t('noAnswer'), dataIndex: 'no_answer' },
          { title: t('loss'), dataIndex: 'loss' },
        ]} />
      </Card>
      <Card style={{ marginTop: 16 }} title={t('trend')} loading={report.isLoading}>
        <Table rowKey="bucket" pagination={false} dataSource={report.data?.trend || []} locale={{ emptyText: t('empty') }} columns={[
          { title: t('bucket'), dataIndex: 'bucket', width: 170 },
          { title: t('periodCalls'), dataIndex: 'calls' },
          { title: t('reachedCalls'), dataIndex: 'reached' },
          { title: t('handoff'), dataIndex: 'handoff' },
          { title: t('completed'), dataIndex: 'completed' },
          { title: t('failed'), dataIndex: 'failed' },
        ]} />
      </Card>
    </>
  )
}

export function GroupMonitorPage() {
  const { t } = useTranslation()
  const [days, setDays] = useState(30)
  const groups = useSecureQuery<AdminContactGroupPayload>(['admin-contact-groups', String(days)], `/api/v1/admin/contact-groups?days=${days}`)

  return (
    <>
      <PageTitle
        title={t('groupMonitor')}
        description={t('groupMonitorHint')}
        action={<Space><Select value={days} onChange={setDays} options={[{ value: 7, label: t('last7Days') }, { value: 30, label: t('last30Days') }, { value: 90, label: t('last90Days') }]} /><Button onClick={() => void groups.refetch()} icon={<ReloadOutlined />}>{t('refresh')}</Button></Space>}
      />
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}><Card loading={groups.isLoading}><Statistic title={t('contacts')} value={groups.data?.summary.contacts || 0} /></Card></Col>
        <Col xs={24} sm={8}><Card loading={groups.isLoading}><Statistic title={t('dnc') + t('Count')} value={groups.data?.summary.dnc_contacts || 0} /></Card></Col>
        <Col xs={24} sm={8}><Card loading={groups.isLoading}><Statistic title={t('totalCalls')} value={groups.data?.summary.calls || 0} /></Card></Col>
      </Row>
      <Card style={{ marginTop: 16 }} title={t('groupMonitorRows')} loading={groups.isLoading}>
        <Table rowKey="key" dataSource={groups.data?.rows || []} locale={{ emptyText: t('empty') }} columns={[
          { title: t('group'), dataIndex: 'label' },
          { title: t('contacts'), dataIndex: 'contacts' },
          { title: t('dncContacts'), dataIndex: 'dnc_contacts' },
          { title: t('calls'), dataIndex: 'calls' },
          { title: t('reachedCalls'), dataIndex: 'reached' },
          { title: t('handoff'), dataIndex: 'handoff' },
          { title: t('completed'), dataIndex: 'completed' },
          { title: t('failed'), dataIndex: 'failed' },
          { title: t('noAnswer'), dataIndex: 'no_answer' },
          { title: t('loss'), dataIndex: 'loss' },
        ]} />
      </Card>
    </>
  )
}

export function BillingPanelPage() {
  const { t } = useTranslation()
  const [dimension, setDimension] = useState<'campaign' | 'agent' | 'line'>('campaign')
  const [days, setDays] = useState(30)
  const [telephonyPrice, setTelephonyPrice] = useState(0)
  const [aiPrice, setAiPrice] = useState(0)
  const [smsPrice, setSmsPrice] = useState(0)
  const billing = useSecureQuery<AdminBillingPayload>(
    ['admin-billing', dimension, String(days), String(telephonyPrice), String(aiPrice), String(smsPrice)],
    `/api/v1/admin/billing?dimension=${dimension}&days=${days}&telephony_unit_price_per_minute=${telephonyPrice}&ai_unit_price_per_minute=${aiPrice}&sms_unit_price=${smsPrice}`,
  )

  return (
    <>
      <PageTitle
        title={t('billing')}
        description={t('billingHint')}
        action={
          <Space>
            <Select value={dimension} options={reportDimensionOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} onChange={setDimension} />
            <Select value={days} onChange={setDays} options={[{ value: 7, label: t('last7Days') }, { value: 30, label: t('last30Days') }, { value: 90, label: t('last90Days') }]} />
            <Button onClick={() => void billing.refetch()} icon={<ReloadOutlined />}>{t('refresh')}</Button>
          </Space>
        }
      />
      <Card>
        <Space wrap>
          <InputNumber addonBefore={t('billingTelephonyPrice')} min={0} precision={6} value={telephonyPrice} onChange={(value) => setTelephonyPrice(Number(value || 0))} />
          <InputNumber addonBefore={t('billingAiPrice')} min={0} precision={6} value={aiPrice} onChange={(value) => setAiPrice(Number(value || 0))} />
          <InputNumber addonBefore={t('billingSmsPrice')} min={0} precision={6} value={smsPrice} onChange={(value) => setSmsPrice(Number(value || 0))} />
        </Space>
      </Card>
      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} sm={6}><Card loading={billing.isLoading}><Statistic title={t('total')} value={billing.data?.summary.calls || 0} /></Card></Col>
        <Col xs={24} sm={6}><Card loading={billing.isLoading}><Statistic title={t('billableCalls')} value={billing.data?.summary.billable_calls || 0} /></Card></Col>
        <Col xs={24} sm={6}><Card loading={billing.isLoading}><Statistic title={t('estimatedCost')} value={billing.data?.summary.estimated_cost || 0} precision={4} prefix={<DollarOutlined />} /></Card></Col>
        <Col xs={24} sm={6}><Card loading={billing.isLoading}><Statistic title={t('aiMinutes')} value={billing.data?.summary.ai_minutes || 0} precision={2} suffix="min" /></Card></Col>
      </Row>
      <Card style={{ marginTop: 16 }} title={t('billingRows')} loading={billing.isLoading}>
        <Table rowKey="key" dataSource={billing.data?.rows || []} locale={{ emptyText: t('empty') }} scroll={{ x: 960 }} columns={[
          { title: t('dimension'), dataIndex: 'label', width: 170 },
          { title: t('calls'), dataIndex: 'calls' },
          { title: t('billableCalls'), dataIndex: 'billable_calls' },
          { title: t('reachedCalls'), dataIndex: 'reached' },
          { title: t('handoff'), dataIndex: 'handoff' },
          { title: t('completed'), dataIndex: 'completed' },
          { title: t('failed'), dataIndex: 'failed' },
          { title: t('noAnswer'), dataIndex: 'no_answer' },
          { title: t('loss'), dataIndex: 'loss' },
          { title: t('aiMinutes'), dataIndex: 'ai_minutes', render: (value) => Number(value || 0).toFixed(2) },
          { title: t('smsCount'), dataIndex: 'sms_count' },
          { title: t('estimatedCost'), dataIndex: 'estimated_cost', render: (value) => Number(value || 0).toFixed(4) },
        ]} />
      </Card>
    </>
  )
}

function ScriptFlowDesigner({ template, open, onClose }: { template: ScriptTemplate | null; open: boolean; onClose: () => void }) {
  const { token } = useAuth()
  const [selectedId, setSelectedId] = useState<number>()
  const [graph, setGraph] = useState<{ nodes: FlowNode[]; edges: FlowEdge[] }>({ nodes: [], edges: [] })
  const [selectedNodeId, setSelectedNodeId] = useState<string>()
  const [edgeForm] = Form.useForm<{ source: string; target: string; condition: FlowEdge['condition']; keywords: string }>()
  const versions = useQuery({
    queryKey: ['script-flow', template?.id],
    queryFn: () => apiRequest<ScriptFlowVersion[]>(`/api/v1/script-templates/${template!.id}/flows`, {}, token),
    enabled: open && Boolean(template && token),
  })
  const selected = versions.data?.find((item) => item.id === selectedId)
  useEffect(() => {
    const item = versions.data?.find((version) => version.id === selectedId) || versions.data?.[0]
    if (item) {
      setSelectedId(item.id)
      setGraph(structuredClone(item.graph))
      setSelectedNodeId(undefined)
    }
  }, [versions.data, selectedId])
  const createVersion = useMutation({
    mutationFn: () => apiRequest<ScriptFlowVersion>(`/api/v1/script-templates/${template!.id}/flows`, { method: 'POST', body: JSON.stringify({ clone_version_id: selected?.id }) }, token),
    onSuccess: async (item) => { message.success('已创建画布草稿'); await versions.refetch(); setSelectedId(item.id) },
    onError: (error) => message.error(error.message),
  })
  const saveVersion = useMutation({
    mutationFn: () => apiRequest<ScriptFlowVersion>(`/api/v1/script-templates/${template!.id}/flows/${selected!.id}`, { method: 'PUT', body: JSON.stringify({ name: selected!.name, description: selected!.description, graph }) }, token),
    onSuccess: async () => { message.success('画布已保存'); await versions.refetch() },
    onError: (error) => message.error(error.message),
  })
  const publishVersion = useMutation({
    mutationFn: () => apiRequest<ScriptFlowVersion>(`/api/v1/script-templates/${template!.id}/flows/${selected!.id}/publish`, { method: 'POST' }, token),
    onSuccess: async () => { message.success('画布版本已发布'); await versions.refetch() },
    onError: (error) => message.error(error.message),
  })
  const selectedNode = graph.nodes.find((node) => node.id === selectedNodeId)
  const addNode = (type: FlowNodeType) => {
    const id = `${type}-${Date.now()}`
    setGraph((current) => ({ ...current, nodes: [...current.nodes, { id, type, label: { message: '播报话术', listen: '等待回答', handoff: '转人工', hangup: '结束通话', start: '开始' }[type], prompt: '', position: { x: 260 + current.nodes.length * 34, y: 90 + (current.nodes.length % 4) * 110 } }] }))
    setSelectedNodeId(id)
  }
  const patchNode = (patch: Partial<FlowNode>) => setGraph((current) => ({ ...current, nodes: current.nodes.map((node) => node.id === selectedNodeId ? { ...node, ...patch } : node) }))
  const deleteNode = () => setGraph((current) => ({ nodes: current.nodes.filter((node) => node.id !== selectedNodeId), edges: current.edges.filter((edge) => edge.source !== selectedNodeId && edge.target !== selectedNodeId) }))
  const startDrag = (event: React.MouseEvent, node: FlowNode) => {
    if (selected?.status !== 'draft') return
    event.preventDefault()
    const origin = { x: event.clientX, y: event.clientY, nodeX: node.position.x, nodeY: node.position.y }
    const move = (moveEvent: MouseEvent) => setGraph((current) => ({
      ...current,
      nodes: current.nodes.map((item) => item.id === node.id ? {
        ...item,
        position: {
          x: Math.max(0, origin.nodeX + moveEvent.clientX - origin.x),
          y: Math.max(0, origin.nodeY + moveEvent.clientY - origin.y),
        },
      } : item),
    }))
    const up = () => { document.removeEventListener('mousemove', move); document.removeEventListener('mouseup', up) }
    document.addEventListener('mousemove', move); document.addEventListener('mouseup', up)
  }
  const addEdge = (values: { source: string; target: string; condition: FlowEdge['condition']; keywords: string }) => {
    if (values.source === values.target) return message.error('起点和终点不能相同')
    setGraph((current) => ({ ...current, edges: [...current.edges, { id: `edge-${Date.now()}`, source: values.source, target: values.target, condition: values.condition, keywords: values.keywords?.split(/[,，]/).map((word) => word.trim()).filter(Boolean) || [] }] }))
    edgeForm.resetFields()
  }
  return <Modal width="96vw" styles={{ body: { height: '76vh', padding: 0 } }} title={`${template?.name || ''} · 话术画布`} open={open} onCancel={onClose} footer={null} destroyOnHidden>
    <div className="flow-designer">
      <div className="flow-toolbar">
        <Select style={{ width: 220 }} value={selectedId} placeholder="选择版本" options={(versions.data || []).map((item) => ({ value: item.id, label: `v${item.version} · ${item.status === 'draft' ? '草稿' : '已发布'}` }))} onChange={(id) => { setSelectedId(id); const item = versions.data?.find((value) => value.id === id); if (item) setGraph(structuredClone(item.graph)) }} />
        <Button onClick={() => createVersion.mutate()} loading={createVersion.isPending}>{selected ? '复制为新草稿' : '创建画布'}</Button>
        <Button type="primary" disabled={selected?.status !== 'draft'} loading={saveVersion.isPending} onClick={() => saveVersion.mutate()}>保存</Button>
        <Popconfirm title="发布后不可直接修改，确认发布？" onConfirm={() => publishVersion.mutate()}><Button disabled={selected?.status !== 'draft'}>发布版本</Button></Popconfirm>
        {selected && <Tag color={selected.status === 'published' ? 'green' : 'blue'}>v{selected.version} {selected.status}</Tag>}
      </div>
      {!selected ? <Empty description="先创建一个画布版本" /> : <div className="flow-workspace">
        <div className="flow-palette"><Text strong>节点</Text>{(['message', 'listen', 'handoff', 'hangup'] as FlowNodeType[]).map((type) => <Button key={type} disabled={selected.status !== 'draft'} onClick={() => addNode(type)}>{({ message: '播报', listen: '等待', handoff: '转人工', hangup: '挂机', start: '开始' } as Record<FlowNodeType, string>)[type]}</Button>)}</div>
        <div className="flow-canvas">
          <svg className="flow-lines">{graph.edges.map((edge) => { const source = graph.nodes.find((node) => node.id === edge.source); const target = graph.nodes.find((node) => node.id === edge.target); return source && target ? <g key={edge.id}><line x1={source.position.x + 76} y1={source.position.y + 30} x2={target.position.x + 76} y2={target.position.y + 30} /><text x={(source.position.x + target.position.x) / 2 + 76} y={(source.position.y + target.position.y) / 2 + 22}>{edge.condition === 'keyword' ? edge.keywords.join('/') : edge.condition}</text></g> : null })}</svg>
          {graph.nodes.map((node) => <button type="button" key={node.id} className={`flow-node flow-node-${node.type} ${selectedNodeId === node.id ? 'selected' : ''}`} style={{ left: node.position.x, top: node.position.y }} onMouseDown={(event) => { setSelectedNodeId(node.id); startDrag(event, node) }}><strong>{node.label}</strong><span>{node.type}</span></button>)}
        </div>
        <div className="flow-inspector">
          {selectedNode ? <><Text strong>节点配置</Text><Input value={selectedNode.label} disabled={selected.status !== 'draft'} onChange={(event) => patchNode({ label: event.target.value })} /><Input.TextArea rows={5} value={selectedNode.prompt} placeholder="播报内容" disabled={selected.status !== 'draft'} onChange={(event) => patchNode({ prompt: event.target.value })} />{selectedNode.type !== 'start' && <Button danger disabled={selected.status !== 'draft'} onClick={deleteNode}>删除节点</Button>}</> : <Text type="secondary">点击节点进行配置</Text>}
          <div className="flow-edge-form"><Text strong>添加连线</Text><Form form={edgeForm} layout="vertical" onFinish={addEdge} initialValues={{ condition: 'always' }}><Form.Item name="source" label="起点" rules={[{ required: true }]}><Select options={graph.nodes.map((node) => ({ value: node.id, label: node.label }))} /></Form.Item><Form.Item name="target" label="终点" rules={[{ required: true }]}><Select options={graph.nodes.map((node) => ({ value: node.id, label: node.label }))} /></Form.Item><Form.Item name="condition" label="条件"><Select options={[{ value: 'always', label: '默认' }, { value: 'keyword', label: '关键词' }, { value: 'silence', label: '静默' }]} /></Form.Item><Form.Item name="keywords" label="关键词（逗号分隔）"><Input /></Form.Item><Button htmlType="submit" disabled={selected.status !== 'draft'} block>添加连线</Button></Form></div>
          <List size="small" header={<Text strong>现有连线</Text>} dataSource={graph.edges} renderItem={(edge) => <List.Item actions={[<Button key="remove" type="text" danger disabled={selected.status !== 'draft'} onClick={() => setGraph((current) => ({ ...current, edges: current.edges.filter((item) => item.id !== edge.id) }))}>删除</Button>]}><Text ellipsis>{edge.source} → {edge.target}<br /><Text type="secondary">{edge.condition}{edge.keywords.length ? ` · ${edge.keywords.join('/')}` : ''}</Text></Text></List.Item>} />
        </div>
      </div>}
    </div>
  </Modal>
}

export function ScriptsPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<ScriptTemplate[]>(['scripts'], '/api/v1/script-templates?page=1&size=100')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ScriptTemplate | null>(null)
  const [flowTemplate, setFlowTemplate] = useState<ScriptTemplate | null>(null)
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
      <Row gutter={[16, 16]}>{(query.data || []).map((item) => <Col xs={24} lg={12} xl={8} key={item.id}><Card loading={query.isLoading} className="script-card" title={<Space><FileTextOutlined /><span>{item.name}</span></Space>} extra={<Switch size="small" checked={item.is_active} onChange={() => toggle.mutate(item)} />} actions={[<Button type="text" icon={<ControlOutlined />} onClick={() => setFlowTemplate(item)}>话术画布</Button>, <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(item)}>{t('edit')}</Button>, <Popconfirm title={t('confirmDelete')} onConfirm={() => remove.mutate(item)}><Button type="text" danger icon={<DeleteOutlined />}>{t('delete')}</Button></Popconfirm>]}><Space wrap><Tag>{item.category}</Tag><Tag>v{item.version}</Tag>{item.tags && <Tag color="blue">{item.tags}</Tag>}</Space><Paragraph ellipsis={{ rows: 4, expandable: true }} className="script-preview">{item.content}</Paragraph><Text type="secondary">{formatDate(item.updated_at)}</Text></Card></Col>)}</Row>
      {!query.isLoading && !query.data?.length && <Card><Empty description={t('empty')} /></Card>}
      <Modal width={680} title={editing ? t('edit') : t('createScript')} open={modalOpen} onCancel={() => { setModalOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={mutation.isPending} destroyOnHidden>
        <Form<ScriptFormValues> form={form} layout="vertical" onFinish={(values) => mutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('name')} name="name" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item label={t('category')} name="category" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
          <Form.Item label={t('content')} name="content" rules={[{ required: true }]}><Input.TextArea rows={8} /></Form.Item>
          <Form.Item label={t('description')} name="description"><Input.TextArea rows={2} /></Form.Item>
          <Row gutter={12}><Col span={18}><Form.Item label={t('tags')} name="tags"><Input /></Form.Item></Col><Col span={6}><Form.Item label={t('enabled')} name="is_active" valuePropName="checked"><Switch /></Form.Item></Col></Row>
        </Form>
      </Modal>
      <ScriptFlowDesigner template={flowTemplate} open={Boolean(flowTemplate)} onClose={() => setFlowTemplate(null)} />
    </>
  )
}

interface CampaignFormValues { name: string; script_template_id?: number; script_flow_version_id?: number; script?: string; mode: CallMode; concurrency: number; retry_limit: number; retry_interval_sec: number; attempt_interval_sec: number; contact_ids: number[]; recording_enabled: boolean; hangup_sms_enabled: boolean; voice_ai_pipeline: 'inherit' | 'legacy' | 'pipecat' }

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
  const selectedTemplateId = Form.useWatch('script_template_id', form)
  const publishedFlows = useQuery({
    queryKey: ['campaign-flow-options', selectedTemplateId],
    queryFn: () => apiRequest<ScriptFlowVersion[]>(`/api/v1/script-templates/${selectedTemplateId}/flows`, {}, token),
    enabled: modalOpen && Boolean(selectedTemplateId && token),
    select: (items) => items.filter((item) => item.status === 'published'),
  })
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
    form.setFieldsValue(campaign || { mode: 'ai_handoff', concurrency: 5, retry_limit: 1, retry_interval_sec: 30, attempt_interval_sec: 1800, recording_enabled: true, hangup_sms_enabled: true, voice_ai_pipeline: 'inherit', contact_ids: [] })
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
          { title: t('voiceAiPipeline'), dataIndex: 'voice_ai_pipeline', width: 130, render: (value) => t(`pipeline_${value}`) },
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
          <Form.Item label={t('voiceAiPipeline')} name="voice_ai_pipeline" extra={t('voiceAiPipelineHint')}><Select options={[{ value: 'inherit', label: t('pipeline_inherit') }, { value: 'legacy', label: 'Legacy' }, { value: 'pipecat', label: 'Pipecat' }]} /></Form.Item>
          <Form.Item label={t('contactsSelected')} name="contact_ids" rules={[{ required: true }]}><Select mode="multiple" optionFilterProp="label" options={(contacts.data || []).map((item) => ({ value: item.id, label: `${item.name || '-'} · ${item.phone}` }))} /></Form.Item>
          <Form.Item label={t('scriptTemplate')} name="script_template_id"><Select allowClear onChange={() => form.setFieldValue('script_flow_version_id', undefined)} options={(scripts.data || []).map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))} /></Form.Item>
          <Form.Item label="已发布话术画布" name="script_flow_version_id" extra="任务锁定具体发布版本；后续草稿修改不会影响运行中的外呼。"><Select allowClear disabled={!selectedTemplateId} placeholder="可选：使用平面话术" options={(publishedFlows.data || []).map((item) => ({ value: item.id, label: `${item.name} · v${item.version}` }))} /></Form.Item>
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
interface CallAnalysisReviewValues { result_code: string; intent: string; sentiment: string; qa_score: number; qa_flags: string; summary: string }

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
  const { token, user } = useAuth()
  const queryClient = useQueryClient()
  const [modalOpen, setModalOpen] = useState(false)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [selectedCall, setSelectedCall] = useState<CallSession | null>(null)
  const [form] = Form.useForm<CallFormValues>()
  const [reviewForm] = Form.useForm<CallAnalysisReviewValues>()
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
  const reviewMutation = useMutation({
    mutationFn: (values: CallAnalysisReviewValues) => apiRequest<CallAnalysis>(`/api/v1/calls/${selectedCall!.id}/analysis`, {
      method: 'PUT',
      body: JSON.stringify({ ...values, qa_flags: values.qa_flags.split(/[,，]/).map((item) => item.trim()).filter(Boolean) }),
    }, token),
    onSuccess: () => {
      message.success(t('reviewSaved'))
      setReviewOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['call-analysis', selectedCall?.id || ''] })
      void queryClient.invalidateQueries({ queryKey: ['admin-dashboard'] })
    },
    onError: (error) => message.error(error.message),
  })
  const openReview = () => {
    if (!analysis.data) return
    let flags = analysis.data.qa_flags_json
    try { flags = (JSON.parse(flags) as string[]).join(', ') } catch { /* keep raw value for correction */ }
    reviewForm.setFieldsValue({
      result_code: analysis.data.result_code,
      intent: analysis.data.intent,
      sentiment: analysis.data.sentiment,
      qa_score: analysis.data.qa_score,
      qa_flags: flags,
      summary: analysis.data.summary,
    })
    setReviewOpen(true)
  }
  const exportEvidence = async () => {
    if (!token || role !== 'admin') return
    try {
      const response = await fetch('/api/v1/admin/calls/export?days=30', {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!response.ok) {
        const body = await response.json().catch(() => ({} as { message?: string; detail?: string }))
        throw new Error(body.message || body.detail || `HTTP ${response.status}`)
      }
      const blob = await response.blob()
      const disposition = response.headers.get('content-disposition') || ''
      const filename = disposition.match(/filename="?([^";]+)"?/i)?.[1] || `call-evidence-${Date.now()}.csv`
      const link = document.createElement('a')
      link.href = URL.createObjectURL(blob)
      link.download = filename
      link.click()
      URL.revokeObjectURL(link.href)
      message.success(t('exportSuccess'))
    } catch (error) {
      message.error(error instanceof Error ? error.message : t('loadFailed'))
    }
  }
  const openCreate = () => { form.setFieldsValue({ mode: 'ai_handoff', max_attempts: 1 }); setModalOpen(true) }
  return (
    <>
      <PageTitle title={t('calls')} description={t('callHint')} action={<Space>{role === 'admin' && <Button icon={<DownloadOutlined />} onClick={() => void exportEvidence()}>{t('exportEvidence')}</Button>}<Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button><Button type="primary" icon={<PhoneOutlined />} onClick={openCreate}>{t('startCall')}</Button></Space>} />
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
          { key: 'analysis', label: t('resultsAndQa'), children: analysis.data ? <Space direction="vertical" size="middle" className="full-width"><Descriptions bordered size="small" column={1} items={[{ key: 'result', label: t('callResult'), children: analysis.data.result_code }, { key: 'intent', label: t('intent'), children: analysis.data.intent }, { key: 'sentiment', label: t('sentiment'), children: analysis.data.sentiment }, { key: 'score', label: t('qaScore'), children: analysis.data.qa_score }, { key: 'flags', label: t('qaFlags'), children: analysis.data.qa_flags_json }, { key: 'review', label: t('reviewState'), children: <StatusTag status={analysis.data.review_state} /> }, { key: 'summary', label: t('summary'), children: analysis.data.summary }]} />{(role === 'admin' || user?.is_supervisor) && <Button type="primary" onClick={openReview}>{t('correctAnalysis')}</Button>}</Space> : <Empty description={analysis.isLoading ? t('loading') : t('empty')} /> },
          { key: 'recordings', label: '录音资产', children: <List loading={recordings.isLoading} dataSource={recordings.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag>{item.state}</Tag><Text>{item.media_format || 'unknown'}</Text><Text type="secondary">{item.duration_sec == null ? '-' : `${item.duration_sec}s`}</Text></Space>} description={item.storage_uri || item.provider_url} /></List.Item>} /> },
          { key: 'metrics', label: '阶段指标', children: <Table<CallMetric> size="small" rowKey="id" pagination={false} loading={metrics.isLoading} dataSource={metrics.data || []} columns={[{ title: '阶段', dataIndex: 'stage' }, { title: '耗时', dataIndex: 'duration_ms', render: (value) => value == null ? '-' : `${value} ms` }, { title: '结果', dataIndex: 'success', render: (value) => <Tag color={value ? 'success' : 'error'}>{value ? '成功' : '失败'}</Tag> }, { title: '错误码', dataIndex: 'error_code', render: (value) => value || '-' }]} /> },
          { key: 'events', label: t('events'), children: <List className="event-list" loading={events.isLoading} dataSource={events.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag>{item.event_type}</Tag><Text type="secondary">{formatDate(item.created_at)}</Text></Space>} description={<pre>{item.payload}</pre>} /></List.Item>} /> },
        ]} />
      </Drawer>
      <Modal title={t('correctAnalysis')} open={reviewOpen} onCancel={() => setReviewOpen(false)} onOk={() => reviewForm.submit()} confirmLoading={reviewMutation.isPending} destroyOnHidden>
        <Form<CallAnalysisReviewValues> form={reviewForm} layout="vertical" onFinish={(values) => reviewMutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('callResult')} name="result_code" rules={[{ required: true }]}><Select options={['interested', 'qualified_lead', 'rejected', 'completed', 'no_answer', 'busy', 'failed'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={12}><Form.Item label={t('intent')} name="intent" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
          <Row gutter={12}><Col span={12}><Form.Item label={t('sentiment')} name="sentiment" rules={[{ required: true }]}><Select options={['positive', 'neutral', 'negative'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={12}><Form.Item label={t('qaScore')} name="qa_score" rules={[{ required: true }]}><InputNumber min={0} max={100} className="full-width" /></Form.Item></Col></Row>
          <Form.Item label={t('qaFlags')} name="qa_flags" extra={t('qaFlagsHint')}><Input /></Form.Item>
          <Form.Item label={t('summary')} name="summary" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}

export function QualityReviewPage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const [reviewState, setReviewState] = useState<'all' | 'auto' | 'reviewed'>('auto')
  const [maxScore, setMaxScore] = useState<number | undefined>()
  const [selected, setSelected] = useState<QualityReviewItem | null>(null)
  const [reviewOpen, setReviewOpen] = useState(false)
  const [reviewForm] = Form.useForm<CallAnalysisReviewValues>()
  const params = new URLSearchParams({ page: '1', size: '200' })
  if (reviewState !== 'all') params.set('review_state', reviewState)
  if (maxScore != null) params.set('max_score', String(maxScore))
  const queue = useSecureQuery<QualityReviewItem[]>(['quality-reviews', reviewState, String(maxScore ?? '')], `/api/v1/quality/reviews?${params}`)
  const speechTurns = useSecureQuery<SpeechTurn[]>(['quality-speech', selected?.call_id || ''], selected ? `/api/v1/calls/${selected.call_id}/speech-turns?final_only=true` : '', Boolean(selected))
  const recordings = useSecureQuery<RecordingAsset[]>(['quality-recordings', selected?.call_id || ''], selected ? `/api/v1/calls/${selected.call_id}/recordings` : '', Boolean(selected))
  const rows = queue.data || []
  const pendingCount = rows.filter((item) => item.review_state === 'auto').length
  const flaggedCount = rows.filter((item) => {
    try { return (JSON.parse(item.qa_flags_json) as string[]).length > 0 } catch { return Boolean(item.qa_flags_json) }
  }).length
  const averageScore = rows.length ? Math.round(rows.reduce((total, item) => total + item.qa_score, 0) / rows.length) : 0

  const reviewMutation = useMutation({
    mutationFn: (values: CallAnalysisReviewValues) => apiRequest<CallAnalysis>(`/api/v1/calls/${selected!.call_id}/analysis`, {
      method: 'PUT',
      body: JSON.stringify({ ...values, qa_flags: values.qa_flags.split(/[,，]/).map((item) => item.trim()).filter(Boolean) }),
    }, token),
    onSuccess: () => {
      message.success(t('reviewSaved'))
      setReviewOpen(false)
      setSelected(null)
      void queryClient.invalidateQueries({ queryKey: ['quality-reviews'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-dashboard'] })
    },
    onError: (error) => message.error(error.message),
  })
  const openReview = (item: QualityReviewItem) => {
    let flags = item.qa_flags_json
    try { flags = (JSON.parse(flags) as string[]).join(', ') } catch { /* preserve raw provider output */ }
    reviewForm.setFieldsValue({
      result_code: item.result_code,
      intent: item.intent,
      sentiment: item.sentiment,
      qa_score: item.qa_score,
      qa_flags: flags,
      summary: item.summary,
    })
    setSelected(item)
    setReviewOpen(true)
  }

  return (
    <>
      <PageTitle title={t('qualityReview')} description={t('qualityReviewHint')} action={<Button icon={<ReloadOutlined />} onClick={() => void queue.refetch()}>{t('refresh')}</Button>} />
      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}><Card><Statistic title={t('pendingReview')} value={pendingCount} /></Card></Col>
        <Col xs={24} md={8}><Card><Statistic title={t('flaggedCalls')} value={flaggedCount} /></Card></Col>
        <Col xs={24} md={8}><Card><Statistic title={t('averageQaScore')} value={averageScore} suffix="/ 100" /></Card></Col>
      </Row>
      <Card className="quality-queue-card">
        <div className="table-toolbar">
          <Select value={reviewState} style={{ width: 160 }} onChange={setReviewState} options={[{ value: 'auto', label: t('pendingReview') }, { value: 'reviewed', label: t('reviewed') }, { value: 'all', label: t('all') }]} />
          <Select allowClear value={maxScore} placeholder={t('scoreThreshold')} style={{ width: 190 }} onChange={setMaxScore} options={[60, 70, 80, 90].map((value) => ({ value, label: t('scoreAtMost', { count: value }) }))} />
        </div>
        <Table<QualityReviewItem> rowKey="call_id" loading={queue.isLoading} dataSource={rows} pagination={{ pageSize: 20 }} scroll={{ x: 1100 }} locale={{ emptyText: t('empty') }} columns={[
          { title: t('phone'), dataIndex: 'phone', width: 150 },
          { title: t('campaigns'), dataIndex: 'campaign_name', width: 170, render: (value, record) => value || (record.campaign_id ? `#${record.campaign_id}` : '-') },
          { title: t('status'), dataIndex: 'call_status', width: 130, render: (value) => <StatusTag status={value} /> },
          { title: t('callResult'), dataIndex: 'result_code', width: 140 },
          { title: t('intent'), dataIndex: 'intent', width: 150, ellipsis: true },
          { title: t('qaScore'), dataIndex: 'qa_score', width: 100, sorter: (a, b) => a.qa_score - b.qa_score, render: (value) => <Tag color={value < 60 ? 'error' : value < 80 ? 'warning' : 'success'}>{value}</Tag> },
          { title: t('reviewState'), dataIndex: 'review_state', width: 120, render: (value) => <StatusTag status={value} /> },
          { title: t('lastUpdated'), dataIndex: 'updated_at', width: 170, render: formatDate },
          { title: t('actions'), fixed: 'right', width: 160, render: (_, record) => <Space><Button size="small" onClick={() => setSelected(record)}>{t('evidence')}</Button><Button size="small" type="primary" onClick={() => openReview(record)}>{record.review_state === 'reviewed' ? t('reReview') : t('review')}</Button></Space> },
        ]} />
      </Card>
      <Drawer width={720} title={`${t('qualityEvidence')} · ${selected?.phone || ''}`} open={Boolean(selected) && !reviewOpen} onClose={() => setSelected(null)} extra={selected && <Button type="primary" onClick={() => openReview(selected)}>{t('review')}</Button>}>
        {selected && <Descriptions bordered size="small" column={1} items={[
          { key: 'result', label: t('callResult'), children: selected.result_code },
          { key: 'intent', label: t('intent'), children: selected.intent },
          { key: 'sentiment', label: t('sentiment'), children: selected.sentiment },
          { key: 'score', label: t('qaScore'), children: selected.qa_score },
          { key: 'flags', label: t('qaFlags'), children: selected.qa_flags_json },
          { key: 'summary', label: t('summary'), children: selected.summary || '-' },
        ]} />}
        <Tabs style={{ marginTop: 16 }} items={[
          { key: 'speech', label: t('transcriptEvidence'), children: <List loading={speechTurns.isLoading} dataSource={speechTurns.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Tag color={item.speaker_role === 'customer' ? 'blue' : 'default'}>{t(item.speaker_role, { defaultValue: item.speaker_role })}</Tag>} description={item.transcript || '-'} /></List.Item>} /> },
          { key: 'recordings', label: t('recordingEvidence'), children: <List loading={recordings.isLoading} dataSource={recordings.data || []} locale={{ emptyText: t('empty') }} renderItem={(item) => <List.Item><List.Item.Meta title={<Space><Tag>{item.state}</Tag><Text>{item.media_format || 'unknown'}</Text></Space>} description={item.storage_uri || item.provider_url || '-'} /></List.Item>} /> },
        ]} />
      </Drawer>
      <Modal title={t('reviewCall')} open={reviewOpen} onCancel={() => setReviewOpen(false)} onOk={() => reviewForm.submit()} confirmLoading={reviewMutation.isPending} destroyOnHidden>
        <Form<CallAnalysisReviewValues> form={reviewForm} layout="vertical" onFinish={(values) => reviewMutation.mutate(values)}>
          <Row gutter={12}><Col span={12}><Form.Item label={t('callResult')} name="result_code" rules={[{ required: true }]}><Select options={['interested', 'qualified_lead', 'rejected', 'completed', 'no_answer', 'busy', 'failed'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={12}><Form.Item label={t('intent')} name="intent" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
          <Row gutter={12}><Col span={12}><Form.Item label={t('sentiment')} name="sentiment" rules={[{ required: true }]}><Select options={['positive', 'neutral', 'negative'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={12}><Form.Item label={t('qaScore')} name="qa_score" rules={[{ required: true }]}><InputNumber min={0} max={100} className="full-width" /></Form.Item></Col></Row>
          <Form.Item label={t('qaFlags')} name="qa_flags" extra={t('qaFlagsHint')}><Input /></Form.Item>
          <Form.Item label={t('summary')} name="summary" rules={[{ required: true }]}><Input.TextArea rows={5} /></Form.Item>
        </Form>
      </Modal>
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
  const unlockMutation = useMutation({
    mutationFn: (item: AdminUser) => apiRequest(`/api/v1/admin/users/${item.id}/unlock`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['admin-users'] }); void queryClient.invalidateQueries({ queryKey: ['audit-logs'] }) },
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
          { title: t('loginSecurity'), width: 130, render: (_, record) => isFutureUtc(record.locked_until) ? <Tag color="red">{t('accountLocked')}</Tag> : <Tag color="green">{t('normal')}</Tag> },
          { title: t('lastLogin'), dataIndex: 'last_login_at', width: 170, render: (value) => value ? formatDate(value) : '-' },
          { title: t('createdAt'), dataIndex: 'created_at', width: 170, render: formatDate },
          { title: t('actions'), fixed: 'right', width: 190, render: (_, record) => <Space><Button type="text" icon={<EditOutlined />} onClick={() => openEdit(record)}>{t('edit')}</Button>{isFutureUtc(record.locked_until) && <Button type="link" loading={unlockMutation.isPending} onClick={() => unlockMutation.mutate(record)}>{t('unlock')}</Button>}</Space> },
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
    ai: <><Row gutter={16}><Col span={6}><Form.Item label={t('aiEnabled')} name="enabled" valuePropName="checked"><Switch /></Form.Item></Col><Col span={6}><Form.Item label={t('externalLlmEnabled')} name="external_llm_enabled" valuePropName="checked"><Switch /></Form.Item></Col><Col span={12}><Form.Item label={t('agentUrl')} name="agent_url" rules={[{ required: true }]}><Input /></Form.Item></Col></Row><Row gutter={16}><Col span={12}><Form.Item label={t('llmProvider')} name="llm_provider"><Select options={[{ value: 'rule', label: '规则模式（本地）' }, { value: 'openai-compatible', label: 'OpenAI-compatible' }]} /></Form.Item></Col><Col span={12}><Form.Item label={t('llmModel')} name="llm_model"><Input /></Form.Item></Col></Row><Row gutter={16}><Col span={8}><Form.Item label={t('asrProvider')} name="asr_provider"><Input /></Form.Item></Col><Col span={8}><Form.Item label={t('ttsProvider')} name="tts_provider"><Input /></Form.Item></Col><Col span={8}><Form.Item label={t('voice')} name="voice"><Input /></Form.Item></Col></Row><Row gutter={16}><Col span={12}><Form.Item label={t('defaultVoiceAiPipeline')} name="voice_ai_pipeline"><Select options={[{ value: 'legacy', label: 'Legacy' }, { value: 'pipecat', label: 'Pipecat' }]} /></Form.Item></Col><Col span={12}><Form.Item label={t('pipecatCanaryPercent')} name="pipecat_canary_percent"><InputNumber min={0} max={100} addonAfter="%" className="full-width" /></Form.Item></Col></Row><Row gutter={16}><Col span={8}><Form.Item label={t('language')} name="language"><Select options={[{ value: 'zh-CN', label: '中文' }, { value: 'en-US', label: 'English' }]} /></Form.Item></Col><Col span={8}><Form.Item label={t('historyTurns')} name="conversation_history_turns"><InputNumber min={1} max={50} className="full-width" /></Form.Item></Col><Col span={8}><Form.Item label={t('maxReplyChars')} name="max_reply_chars"><InputNumber min={20} max={2000} className="full-width" /></Form.Item></Col></Row><Form.Item label={t('forbiddenPhrases')} name="forbidden_phrases"><Input placeholder="保证收益,百分百" /></Form.Item><Form.Item label={t('fallbackReply')} name="fallback_reply" rules={[{ required: true }]}><Input.TextArea rows={2} /></Form.Item></>,
    sms: <><Form.Item label={t('smsEnabled')} name="enabled" valuePropName="checked"><Switch /></Form.Item><Row gutter={16}><Col span={12}><Form.Item label={t('provider')} name="provider"><Select options={[{ value: 'mock', label: 'Mock（仅测试）' }, { value: 'http', label: 'HTTP Bridge' }]} /></Form.Item></Col><Col span={12}><Form.Item label={t('senderId')} name="sender_id"><Input /></Form.Item></Col></Row><Form.Item label={t('endpoint')} name="endpoint"><Input /></Form.Item><Form.Item label={t('hangupTemplate')} name="hangup_template"><Input.TextArea rows={4} /></Form.Item></>,
    compliance: <><Alert type="info" showIcon message={t('complianceRetentionHint')} style={{ marginBottom: 16 }} /><Row gutter={16}><Col xs={12} lg={6}><Form.Item label={t('dncEnforced')} name="dnc_enforced" valuePropName="checked"><Switch /></Form.Item></Col><Col xs={12} lg={6}><Form.Item label={t('requireExplicitConsent')} name="require_explicit_consent" valuePropName="checked"><Switch /></Form.Item></Col><Col xs={12} lg={6}><Form.Item label={t('recordingNotice')} name="recording_notice" valuePropName="checked"><Switch /></Form.Item></Col><Col xs={12} lg={6}><Form.Item label={t('maxAttemptsDay')} name="max_attempts_per_day" rules={[{ required: true }]}><InputNumber min={1} max={20} className="full-width" /></Form.Item></Col></Row><Form.Item label={t('recordingNoticeText')} name="recording_notice_text" rules={[{ required: true, max: 500 }]}><Input.TextArea rows={2} maxLength={500} showCount /></Form.Item><Row gutter={16}><Col xs={24} lg={12}><Form.Item label={t('allowedPhonePrefixes')} name="allowed_phone_prefixes" extra={t('allowedPhonePrefixesHint')}><Input placeholder="86,63" /></Form.Item></Col><Col xs={24} lg={12}><Form.Item label={t('maxCallsPerDay')} name="max_calls_per_day" rules={[{ required: true }]}><InputNumber min={1} max={10000000} className="full-width" /></Form.Item></Col></Row><Row gutter={16}><Col xs={24} lg={8}><Form.Item label={t('minAttemptInterval')} name="min_attempt_interval_sec" rules={[{ required: true }]} extra={t('minAttemptIntervalHint')}><InputNumber min={0} max={604800} addonAfter={t('seconds')} className="full-width" /></Form.Item></Col><Col xs={24} lg={8}><Form.Item label={t('recordingRetentionDays')} name="recording_retention_days" rules={[{ required: true }]}><InputNumber min={1} max={3650} addonAfter={t('day')} className="full-width" /></Form.Item></Col><Col xs={24} lg={8}><Form.Item label={t('partialTranscriptRetentionHours')} name="partial_transcript_retention_hours" rules={[{ required: true }]}><InputNumber min={1} max={720} addonAfter={t('hour')} className="full-width" /></Form.Item></Col></Row><Row gutter={16}><Col xs={24} lg={12}><Form.Item label={t('finalTranscriptRetentionDays')} name="final_transcript_retention_days" rules={[{ required: true }]}><InputNumber min={1} max={3650} addonAfter={t('day')} className="full-width" /></Form.Item></Col><Col xs={24} lg={12}><Form.Item label={t('callSensitiveDataRetentionDays')} name="call_sensitive_data_retention_days" rules={[{ required: true }]}><InputNumber min={1} max={3650} addonAfter={t('day')} className="full-width" /></Form.Item></Col></Row><Row gutter={16}><Col xs={24} lg={8}><Form.Item label={t('startHour')} name="allowed_start_hour" rules={[{ required: true }]}><InputNumber min={0} max={23} className="full-width" /></Form.Item></Col><Col xs={24} lg={8}><Form.Item label={t('endHour')} name="allowed_end_hour" rules={[{ required: true }]}><InputNumber min={0} max={23} className="full-width" /></Form.Item></Col><Col xs={24} lg={8}><Form.Item label={t('timezone')} name="timezone" rules={[{ required: true }]}><Input /></Form.Item></Col></Row></>,
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

export function KnowledgePage() {
  const { t } = useTranslation()
  const { token } = useAuth()
  const queryClient = useQueryClient()
  const query = useSecureQuery<KnowledgeItem[]>(['knowledge'], '/api/v1/knowledge')
  const [editing, setEditing] = useState<KnowledgeItem | null>(null)
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm<Partial<KnowledgeItem>>()
  const saveMutation = useMutation({
    mutationFn: (values: Partial<KnowledgeItem>) => apiRequest<KnowledgeItem>(editing ? `/api/v1/knowledge/${editing.id}` : '/api/v1/knowledge', { method: editing ? 'PUT' : 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); setOpen(false); setEditing(null); form.resetFields(); void queryClient.invalidateQueries({ queryKey: ['knowledge'] }) },
    onError: (error) => message.error(error.message),
  })
  const disableMutation = useMutation({
    mutationFn: (item: KnowledgeItem) => apiRequest<KnowledgeItem>(`/api/v1/knowledge/${item.id}`, { method: 'DELETE' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['knowledge'] }) },
    onError: (error) => message.error(error.message),
  })
  const edit = (item?: KnowledgeItem) => {
    setEditing(item || null)
    form.setFieldsValue(item || { category: 'default', tags: '', is_active: true })
    setOpen(true)
  }
  return <>
    <PageTitle title={t('knowledge')} description={t('knowledgeHint')} action={<Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>{t('create')}</Button>} />
    <Card><Table<KnowledgeItem> rowKey="id" loading={query.isLoading} dataSource={query.data || []} columns={[
      { title: t('name'), dataIndex: 'title', width: 220 },
      { title: t('category'), dataIndex: 'category', width: 140 },
      { title: t('content'), dataIndex: 'content', ellipsis: true },
      { title: t('version'), dataIndex: 'version', width: 90 },
      { title: t('status'), dataIndex: 'is_active', width: 100, render: (value) => <Tag color={value ? 'success' : 'default'}>{value ? t('enabled') : t('deleted')}</Tag> },
      { title: t('actions'), width: 170, render: (_, item) => <Space><Button size="small" icon={<EditOutlined />} onClick={() => edit(item)}>{t('edit')}</Button><Popconfirm title={t('confirmDelete')} onConfirm={() => disableMutation.mutate(item)}><Button size="small" danger icon={<DeleteOutlined />} disabled={!item.is_active}>{t('delete')}</Button></Popconfirm></Space> },
    ]} /></Card>
    <Modal title={editing ? t('edit') : t('create')} open={open} onCancel={() => setOpen(false)} onOk={() => form.submit()} confirmLoading={saveMutation.isPending} destroyOnHidden>
      <Form form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
        <Form.Item name="title" label={t('name')} rules={[{ required: true }]}><Input /></Form.Item>
        <Form.Item name="category" label={t('category')}><Input /></Form.Item>
        <Form.Item name="tags" label={t('tags')}><Input /></Form.Item>
        <Form.Item name="content" label={t('content')} rules={[{ required: true }]}><Input.TextArea rows={10} /></Form.Item>
        <Form.Item name="is_active" label={t('enabled')} valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </>
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
      <Card title={t('runtimeMetrics')} className="audit-card" loading={overview.isLoading}><Row gutter={[16, 16]}><Col xs={24} lg={6}><Statistic title={t('averageAiLatency')} value={overview.data?.operations?.average_ai_turn_ms ?? 0} suffix="ms" /></Col><Col xs={24} lg={6}><Statistic title={t('staleTasks')} value={overview.data?.operations?.stale_processing_tasks ?? 0} /></Col><Col xs={24} lg={6}><Statistic title={t('oldestTask')} value={overview.data?.operations?.oldest_open_task_age_sec ?? 0} suffix="s" /></Col><Col xs={24} lg={6}><Statistic title={t('recordingDeleteFailures')} value={overview.data?.operations?.recording_deletion_failures ?? 0} /></Col><Col span={24}><Space wrap>{Object.entries(overview.data?.operations?.durable_tasks || {}).map(([key, value]) => <Tag key={key} color={key === 'dead' ? 'error' : key === 'failed' ? 'warning' : 'blue'}>{key}: {value}</Tag>)}{!Object.keys(overview.data?.operations?.durable_tasks || {}).length && <Text type="secondary">{t('empty')}</Text>}</Space></Col></Row></Card>
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
  const handoffQueue = useSecureQuery<HandoffRequest[]>(['handoffs', 'waiting'], '/api/v1/handoffs?state=waiting')
  const [form] = Form.useForm<CallFormValues>()
  const [presenceStatus, setPresenceStatus] = useState<'ready' | 'busy' | 'offline'>(user?.agent_status || 'ready')
  const presenceRef = useRef(presenceStatus)
  const [presenceSaving, setPresenceSaving] = useState(false)
  const [presenceError, setPresenceError] = useState(false)
  useEffect(() => { presenceRef.current = presenceStatus }, [presenceStatus])
  const savePresence = useCallback(async (status: 'ready' | 'busy' | 'offline', notifyFailure = true) => {
    if (!token) return false
    const previous = presenceRef.current
    setPresenceStatus(status)
    presenceRef.current = status
    setPresenceSaving(true)
    try {
      const saved = await apiRequest<User>('/api/v1/auth/presence', { method: 'PUT', body: JSON.stringify({ status }) }, token)
      setPresenceStatus(saved.agent_status)
      presenceRef.current = saved.agent_status
      setPresenceError(false)
      return true
    } catch (error) {
      setPresenceStatus(previous)
      presenceRef.current = previous
      setPresenceError(true)
      if (notifyFailure) message.error(error instanceof Error ? error.message : t('presenceUpdateFailed'))
      return false
    } finally {
      setPresenceSaving(false)
    }
  }, [token, t])
  useEffect(() => {
    if (!token || !user || user.role !== 'agent') return
    const heartbeat = window.setInterval(() => void savePresence(presenceRef.current, false), 30_000)
    return () => window.clearInterval(heartbeat)
  }, [token, user?.id])
  const mutation = useMutation({
    mutationFn: (values: CallFormValues) => apiRequest<CallSession>('/api/v1/calls', { method: 'POST', body: JSON.stringify(values) }, token),
    onSuccess: () => { message.success(t('operationSuccess')); form.resetFields(); form.setFieldsValue({ mode: 'human_only', max_attempts: 1 }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const handoffMutation = useMutation({
    mutationFn: (call: CallSession) => apiRequest<CallSession>(`/api/v1/calls/${call.id}/handover?reason=agent_workspace`, { method: 'POST' }, token),
    onSuccess: () => { message.success(t('operationSuccess')); void savePresence('busy'); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const respondHandoff = useMutation({
    mutationFn: ({ item, action }: { item: HandoffRequest; action: 'accept' | 'reject' }) => apiRequest<HandoffRequest>(`/api/v1/calls/${item.call_session_id}/handoffs/${item.id}/${action}`, { method: 'POST' }, token),
    onSuccess: (_, variables) => { if (variables.action === 'accept') { setPresenceStatus('busy'); presenceRef.current = 'busy'; setPresenceError(false) } message.success(t('operationSuccess')); void queryClient.invalidateQueries({ queryKey: ['handoffs'] }); void queryClient.invalidateQueries({ queryKey: ['calls'] }) },
    onError: (error) => message.error(error.message),
  })
  const activeCalls = useMemo(() => (query.data || []).filter((item) => !['completed', 'failed', 'no_answer', 'busy', 'voicemail'].includes(item.status)), [query.data])
  const activePhoneCall = useMemo(() => activeCalls.find((item) => ['in_human', 'handoff_transferring', 'answered'].includes(item.status)) || activeCalls[0], [activeCalls])
  const refreshWorkspace = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ['handoffs'] })
    void queryClient.invalidateQueries({ queryKey: ['calls'] })
    if (activePhoneCall?.id) {
      void queryClient.invalidateQueries({ queryKey: ['softphone-speech', activePhoneCall.id] })
      void queryClient.invalidateQueries({ queryKey: ['softphone-analysis', activePhoneCall.id] })
    }
  }, [activePhoneCall?.id, queryClient])
  const mediaRegistered = useCallback(() => { void savePresence('ready', false) }, [savePresence])

  return (
    <>
      <PageTitle title={t('workspace')} description={t('workbenchHint')} />
      {presenceError && <Alert type="error" showIcon closable message={t('presenceUpdateFailed')} description={t('presenceUpdateFailedHint')} action={<Button size="small" onClick={() => void savePresence(presenceStatus)}>{t('retry')}</Button>} style={{ marginBottom: 16 }} />}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          {token && <WebRtcSoftphone token={token} activeCall={activePhoneCall} onRegistered={mediaRegistered} onPlatformUpdate={refreshWorkspace} />}
          <Card className="dialer-card" title={<Space><PhoneOutlined />{t('callNow')}</Space>}>
            <Form<CallFormValues> form={form} layout="vertical" initialValues={{ mode: 'human_only', max_attempts: 1 }} onFinish={(values) => mutation.mutate(values)}>
              <Form.Item label={t('phone')} name="phone" rules={[{ required: true }, { pattern: /^\+?[0-9 ()-]{6,32}$/, message: t('invalidPhone') }]}><Input size="large" placeholder="13800000000" /></Form.Item>
              <Form.Item label={t('mode')} name="mode"><Select size="large" options={modeOptions.map((item) => ({ value: item.value, label: t(item.labelKey) }))} /></Form.Item>
              <Form.Item label={t('attempts')} name="max_attempts"><InputNumber min={1} max={10} size="large" className="full-width" /></Form.Item>
              <Button type="primary" size="large" block icon={<PhoneOutlined />} htmlType="submit" loading={mutation.isPending} disabled={presenceStatus !== 'ready'}>{t('callNow')}</Button>
            </Form>
          </Card>
          <Card className="agent-profile-card" title={t('profile')}>
            <Descriptions column={1} size="small" items={[{ key: 'name', label: t('contactName'), children: user?.full_name }, { key: 'id', label: t('username'), children: user?.username }, { key: 'status', label: t('agentStatus'), children: <Select value={presenceStatus} loading={presenceSaving} disabled={presenceSaving} style={{ width: 140 }} onChange={(value) => void savePresence(value)} options={[{ value: 'ready', label: t('agentReady') }, { value: 'busy', label: t('agentBusy') }, { value: 'offline', label: t('agentOffline') }]} /> }]} />
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card title={t('handoffQueue')} extra={<Button icon={<ReloadOutlined />} onClick={() => void handoffQueue.refetch()}>{t('refresh')}</Button>} style={{ marginBottom: 16 }}>
            {(handoffQueue.data || []).length ? <List dataSource={handoffQueue.data} renderItem={(item) => <List.Item className="handoff-item" actions={[<Popconfirm key="accept" title={t('confirmAcceptHandoff')} description={item.summary || item.last_customer_utterance || undefined} onConfirm={() => respondHandoff.mutate({ item, action: 'accept' })} disabled={presenceStatus !== 'ready'}><Button type="primary" loading={respondHandoff.isPending} disabled={presenceStatus !== 'ready'}>{t('accept')}</Button></Popconfirm>, <Popconfirm key="reject" title={t('confirmRejectHandoff')} onConfirm={() => respondHandoff.mutate({ item, action: 'reject' })}><Button danger disabled={respondHandoff.isPending}>{t('reject')}</Button></Popconfirm>]}><List.Item.Meta title={<Space wrap><Text strong>{item.contact_name || t('unknownCustomer')}</Text><Text>{item.phone || item.call_session_id}</Text><StatusTag status="waiting_human" /></Space>} description={<Space direction="vertical" size={3}><Text type="secondary">{item.campaign_name || t('noCampaign')} · {t(modeOptions.find((option) => option.value === item.mode)?.labelKey || item.mode || 'unknown')} · {t('waitedSeconds', { count: item.wait_seconds || 0 })}</Text>{item.intent && <Text><strong>{t('intent')}：</strong>{item.intent}</Text>}{item.last_customer_utterance && <Text><strong>{t('lastCustomerUtterance')}：</strong>{item.last_customer_utterance}</Text>}{item.summary && <Text type="secondary"><strong>{t('summary')}：</strong>{item.summary}</Text>}</Space>} /></List.Item>} /> : <Empty description={handoffQueue.isLoading ? t('loading') : t('empty')} />}
          </Card>
          <Card title={t('activeQueue')} extra={<Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>{t('refresh')}</Button>}>
            {activeCalls.length ? <List dataSource={activeCalls} renderItem={(call) => <List.Item actions={[<Button key="handoff" type="primary" ghost loading={handoffMutation.isPending} onClick={() => handoffMutation.mutate(call)} disabled={presenceStatus !== 'ready' || !['dialing', 'answered', 'in_ai', 'waiting_human'].includes(call.status)}>{t('handoff')}</Button>]}><List.Item.Meta avatar={<div className="call-avatar"><PhoneOutlined /></div>} title={<Space>{call.phone}<StatusTag status={call.status} /></Space>} description={`${t('mode')}: ${t(modeOptions.find((item) => item.value === call.mode)?.labelKey || call.mode)} · ${formatDate(call.updated_at)}`} /></List.Item>} /> : <Empty description={t('empty')} />}
          </Card>
        </Col>
      </Row>
    </>
  )
}
