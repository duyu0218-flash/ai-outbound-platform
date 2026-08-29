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

export type FlowNodeType = 'start' | 'message' | 'listen' | 'handoff' | 'hangup'
export interface FlowNode { id: string; type: FlowNodeType; label: string; prompt: string; position: { x: number; y: number } }
export interface FlowEdge { id: string; source: string; target: string; condition: 'always' | 'keyword' | 'silence'; keywords: string[] }
export interface ScriptFlowVersion {
  id: number
  tenant_id: number
  script_template_id: number
  version: number
  name: string
  description: string
  status: 'draft' | 'published' | 'archived'
  graph: { nodes: FlowNode[]; edges: FlowEdge[] }
  published_at?: string
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
  script_flow_version_id?: number
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
  script_flow_version_id?: number
  flow_node_key?: string
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

export interface SpeechTurn {
  id: number
  call_session_id: string
  turn_index: number
  speaker_role: 'customer' | 'ai' | 'agent' | 'system'
  transcript: string
  is_final: boolean
  confidence?: number
  start_ms?: number
  end_ms?: number
  asr_provider: string
  created_at: string
}

export interface CallMetric {
  id: number
  stage: string
  provider: string
  duration_ms?: number
  success: boolean
  error_code?: string
  detail: string
  created_at: string
}

export interface RecordingAsset {
  id: number
  provider_url: string
  storage_uri: string
  state: string
  duration_sec?: number
  media_format: string
  channel_count: number
  created_at: string
}

export interface KnowledgeItem {
  id: number
  title: string
  content: string
  category: string
  tags: string
  version: number
  is_active: boolean
  created_at: string
  updated_at: string
}

export interface HandoffRequest {
  id: number
  call_session_id: string
  assigned_agent_id?: number
  state: 'waiting' | 'accepting' | 'accepted' | 'rejected' | 'completed' | 'expired'
  reason: string
  target_group: string
  requested_at: string
  responded_at?: string
  phone?: string
  mode?: CallMode
  campaign_id?: number
  contact_name?: string
  campaign_name?: string
  intent?: string
  summary: string
  last_customer_utterance: string
  wait_seconds?: number
}

export interface QualityReviewItem {
  call_id: string
  phone: string
  call_status: string
  campaign_id?: number
  campaign_name?: string
  result_code: string
  sentiment: string
  intent: string
  summary: string
  qa_score: number
  qa_flags_json: string
  review_state: 'auto' | 'reviewed'
  reviewed_by?: number
  reviewed_at?: string
  updated_at: string
}

export interface CallAnalysis {
  id: number
  result_code: string
  sentiment: string
  intent: string
  summary: string
  qa_score: number
  qa_flags_json: string
  structured_json: string
  review_state: string
  updated_at: string
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
  period: {
    days: number
    since: string
    calls: number
    reached: number
    completed: number
    failed: number
    analyzed: number
    interested: number
    pending_reviews: number
    average_qa_score: number
    reach_rate: number
    interest_rate: number
    completion_rate: number
  }
  campaign_performance: Array<{
    campaign_id: number
    name: string
    calls: number
    reached: number
    interested: number
    reach_rate: number
    interest_rate: number
  }>
  metric_definitions: Record<string, string>
}

export interface RuntimeInfo {
  app_name: string
  demo_users_enabled: boolean
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
  operations: {
    durable_tasks: Record<string, number>
    average_ai_turn_ms?: number
    stale_processing_tasks: number
    oldest_open_task_age_sec: number
    recording_deletion_failures: number
  }
  generated_at: string
}
