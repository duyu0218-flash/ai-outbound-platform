import {
  ApiOutlined,
  AuditOutlined,
  BarChartOutlined,
  CustomerServiceOutlined,
  FileTextOutlined,
  GlobalOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PhoneOutlined,
  SoundOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons'
import { Avatar, Badge, Button, Dropdown, Layout, Menu, Select, Space, Spin, Tag, Typography } from 'antd'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { apiRequest } from './api'
import { useAuth } from './auth'
import { setLanguage } from './i18n'
import type { Role } from './types'

const { Header, Sider, Content } = Layout

export function ProtectedRoute({ role }: { role: Role }) {
  const { user, loading } = useAuth()
  if (loading) return <div className="app-loading"><Spin size="large" /></div>
  if (!user) return <Navigate to={`/${role}/login`} replace />
  if (role === 'admin' && user.role !== 'admin') return <Navigate to="/agent" replace />
  return <Outlet />
}

const adminNavigation = [
  { key: '/admin', icon: <BarChartOutlined />, labelKey: 'dashboard' },
  { key: '/admin/contacts', icon: <TeamOutlined />, labelKey: 'contacts' },
  { key: '/admin/scripts', icon: <FileTextOutlined />, labelKey: 'scripts' },
  { key: '/admin/campaigns', icon: <SoundOutlined />, labelKey: 'campaigns' },
  { key: '/admin/calls', icon: <PhoneOutlined />, labelKey: 'calls' },
  { key: '/admin/users', icon: <TeamOutlined />, labelKey: 'users' },
  { key: '/admin/lines', icon: <CustomerServiceOutlined />, labelKey: 'lines' },
  { key: '/admin/settings', icon: <SettingOutlined />, labelKey: 'settings' },
  { key: '/admin/system', icon: <AuditOutlined />, labelKey: 'system' },
]

const agentNavigation = [
  { key: '/agent', icon: <CustomerServiceOutlined />, labelKey: 'workspace' },
  { key: '/agent/calls', icon: <PhoneOutlined />, labelKey: 'calls' },
]

export function AppShell({ role }: { role: Role }) {
  const [collapsed, setCollapsed] = useState(false)
  const [healthy, setHealthy] = useState<boolean | null>(null)
  const { t, i18n } = useTranslation()
  const { user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const navigation = role === 'admin' ? adminNavigation : agentNavigation

  useEffect(() => {
    apiRequest<{ status: string }>('/healthz')
      .then(() => setHealthy(true))
      .catch(() => setHealthy(false))
  }, [location.pathname])

  const currentItem = useMemo(
    () => [...navigation].sort((a, b) => b.key.length - a.key.length).find((item) => location.pathname === item.key || location.pathname.startsWith(`${item.key}/`)),
    [location.pathname, navigation],
  )

  const accountMenu = {
    items: [
      { key: 'profile', icon: <UserOutlined />, label: `${user?.full_name || user?.username} · ${user?.role}` },
      { key: 'docs', icon: <ApiOutlined />, label: t('apiDocs'), onClick: () => window.open('/docs.html', '_blank', 'noopener,noreferrer') },
      { type: 'divider' as const },
      { key: 'logout', danger: true, icon: <LogoutOutlined />, label: t('logout'), onClick: async () => { await logout(); navigate(`/${role}/login`, { replace: true }) } },
    ],
  }

  return (
    <Layout className="app-shell">
      <Sider width={232} collapsedWidth={76} collapsed={collapsed} className="app-sider" trigger={null}>
        <div className="brand" onClick={() => navigate(`/${role}`)}>
          <div className="brand-mark"><SoundOutlined /></div>
          {!collapsed && <div><strong>{t('product')}</strong><span>{role === 'admin' ? t('adminPortal') : t('agentPortal')}</span></div>}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[currentItem?.key || `/${role}`]}
          items={navigation.map((item) => ({ ...item, label: t(item.labelKey) }))}
          onClick={({ key }) => navigate(key)}
        />
        {!collapsed && (
          <div className="sider-footer">
            <span>{t('tenant')} #{user?.tenant_id}</span>
            <span>{t('role')}: {user?.role}</span>
          </div>
        )}
      </Sider>
      <Layout>
        <Header className="app-header">
          <Space size="middle">
            <Button type="text" className="collapse-button" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => setCollapsed(!collapsed)} />
            <div>
              <Typography.Title level={4}>{t(currentItem?.labelKey || (role === 'admin' ? 'dashboard' : 'workspace'))}</Typography.Title>
              <Typography.Text type="secondary">{role === 'admin' ? t('adminPortal') : t('agentPortal')}</Typography.Text>
            </div>
          </Space>
          <Space size="large">
            <Badge status={healthy === false ? 'error' : healthy ? 'success' : 'processing'} text={healthy === false ? t('offline') : t('online')} />
            <Select
              aria-label={t('language')}
              variant="borderless"
              value={i18n.language.startsWith('en') ? 'en-US' : 'zh-CN'}
              suffixIcon={<GlobalOutlined />}
              options={[{ value: 'zh-CN', label: '中文' }, { value: 'en-US', label: 'English' }]}
              onChange={setLanguage}
            />
            <Dropdown menu={accountMenu} placement="bottomRight">
              <Button type="text" className="account-button" aria-label={t('profile')}>
                <Avatar size="small" icon={<UserOutlined />} />
                <span>{user?.full_name || user?.username}</span>
                <Tag color={user?.role === 'admin' ? 'blue' : 'cyan'}>{user?.role}</Tag>
              </Button>
            </Dropdown>
          </Space>
        </Header>
        <Content className="app-content"><Outlet /></Content>
      </Layout>
    </Layout>
  )
}
