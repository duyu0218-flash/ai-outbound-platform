import { ConfigProvider, Result, Button, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import enUS from 'antd/locale/en_US'
import { useTranslation } from 'react-i18next'
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { AppShell, ProtectedRoute } from './layout'
import {
  AgentWorkspacePage,
  CallsPage,
  CampaignsPage,
  ContactsPage,
  DashboardPage,
  LinesPage,
  LoginPage,
  KnowledgePage,
  ScriptsPage,
  SettingsPage,
  SystemPage,
  UsersPage,
} from './pages'

function NotFoundPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  return <Result status="404" title="404" subTitle={t('noPermission')} extra={<Button type="primary" onClick={() => navigate('/')}>{t('backHome')}</Button>} />
}

export default function App() {
  const { i18n } = useTranslation()
  return (
    <ConfigProvider locale={i18n.language.startsWith('en') ? enUS : zhCN} theme={{
      token: { colorPrimary: '#2f6bff', borderRadius: 10, colorBgLayout: '#f4f7fb', fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif" },
      components: { Layout: { siderBg: '#0b1736', headerBg: '#ffffff' }, Menu: { darkItemBg: '#0b1736', darkItemSelectedBg: '#2457d6' } },
    }}>
      <AntApp>
        <Routes>
          <Route path="/" element={<Navigate to="/admin" replace />} />
          <Route path="/admin/login" element={<LoginPage role="admin" />} />
          <Route path="/agent/login" element={<LoginPage role="agent" />} />

          <Route element={<ProtectedRoute role="admin" />}>
            <Route path="/admin" element={<AppShell role="admin" />}>
              <Route index element={<DashboardPage />} />
              <Route path="contacts" element={<ContactsPage />} />
              <Route path="scripts" element={<ScriptsPage />} />
              <Route path="campaigns" element={<CampaignsPage />} />
              <Route path="calls" element={<CallsPage role="admin" />} />
              <Route path="users" element={<UsersPage />} />
              <Route path="lines" element={<LinesPage />} />
              <Route path="knowledge" element={<KnowledgePage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="system" element={<SystemPage />} />
            </Route>
          </Route>

          <Route element={<ProtectedRoute role="agent" />}>
            <Route path="/agent" element={<AppShell role="agent" />}>
              <Route index element={<AgentWorkspacePage />} />
              <Route path="calls" element={<CallsPage role="agent" />} />
            </Route>
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </AntApp>
    </ConfigProvider>
  )
}
