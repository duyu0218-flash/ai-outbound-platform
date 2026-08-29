export type Role = 'admin' | 'agent'

export interface User {
  id: number
  tenant_id: number
  username: string
  full_name: string
  role: Role
  is_supervisor: boolean
  agent_status: 'ready' | 'busy' | 'offline'
  last_seen_at?: string
  enabled: boolean
}

export interface LoginResponse {
  access_token: string
  token_type: string
  username: string
  role: Role
  tenant_id: number
}

export interface Contact {
  id: number
  tenant_id: number
  phone: string
  name?: string
  tags: string
  consent_state: 'consented' | 'not_consented' | 'revoked' | 'unknown'
  dnc: boolean
  timezone?: string
  created_at: string
  updated_at: string
}

export interface ScriptTemplate {
  id: number
  tenant_id: number
  name: string
  content: string
  category: string
  description: string
  tags: string
  is_active: boolean
  version: number
  created_at: string
  updated_at: string
}

export type CallMode = 'human_only' | 'ai_only' | 'ai_handoff' | 'ai_with_sms' | 'mixed_human_first'

export interface Campaign {
  id: number
  tenant_id: number
  name: string
  script: string
  script_template_id?: number
  mode: CallMode
  concurrency: number
  retry_limit: number
  retry_interval_sec: number
  attempt_interval_sec: number
  recording_enabled: boolean
  hangup_sms_enabled: boolean
  contact_ids: number[]
  status: string
  created_at: string
  updated_at: string
}

export interface CallSession {
  id: string
  phone: string
  mode: CallMode
  status: string
  attempts: number
  max_attempts: number
  campaign_id?: number
  contact_id?: number
  handoff_reason?: string
  human_agent_id?: number
  telephony_line_id?: number
  recording_url?: string
  last_error?: string
  created_at: string
  updated_at: string
}

export interface CallEvent {
  id: number
  call_session_id: string
  event_type: string
  source: string
  payload: string
  created_at: string
}

export interface AdminDashboard {
  scope: 'admin'
  message: string
  stats: {
    contacts: number
    active_scripts: number
    campaigns: number
    calls: number
  }
}

export interface AdminUser extends User {
  phone?: string
  created_at: string
  updated_at: string
}

export interface TelephonyLine {
  id: number
  tenant_id: number
  name: string
  provider: string
  gateway_url: string
  caller_id: string
  max_concurrency: number
  priority: number
  weight: number
  credential_ref: string
  enabled: boolean
  created_at: string
  updated_at: string
}

export interface SmsLog {
  id: number
  tenant_id: number
  call_session_id?: string
  to_phone: string
  template_code?: string
  content: string
  state: string
  provider_message_id?: string
  provider_error?: string
  sent_at?: string
  created_at: string
}

export type SettingSection = 'capacity' | 'ai' | 'sms' | 'compliance' | 'integration'

export interface AdminSetting {
  section: SettingSection
  data: Record<string, string | number | boolean>
  updated_at?: string
}

export interface AuditLog {
  id: number
  tenant_id: number
  actor_user_id?: number
  actor_username: string
  action: string
  resource_type: string
  resource_id?: string
  detail: string
  created_at: string
}

export interface SystemOverview {
  services: Record<string, string>
  resources: {
    users: number
    enabled_users: number
    lines: number
    enabled_lines: number
  }
  call_statuses: Record<string, number>
  capacity: {
    configured_max_concurrent_calls: number
    line_max_concurrency?: number
    effective_max_concurrent_calls: number
    active_calls: number
    available_slots: number
    limiting_source: 'tenant_capacity' | 'telephony_line' | 'tenant_and_line'
    telephony_provider: string
    environment_default: number
  }
  generated_at: string
}
