export type Role = 'admin' | 'agent'

export interface User {
  id: number
  tenant_id: number
  username: string
  full_name: string
  role: Role
  is_supervisor: boolean
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
