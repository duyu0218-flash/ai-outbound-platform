import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Descriptions, Divider, Select, Space, Tag, Typography, message } from 'antd'
import {
  AudioMutedOutlined,
  AudioOutlined,
  DisconnectOutlined,
  PauseCircleOutlined,
  PhoneOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { SimpleUser, type SimpleUserDelegate, type SimpleUserOptions } from 'sip.js/lib/platform/web'
import { apiRequest } from './api'
import type { AgentMediaStatus, CallAnalysis, CallSession, SpeechTurn, WebRtcSessionConfig } from './types'

const { Text, Title } = Typography
const DTMF_TONES = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#']

interface DeviceOption {
  label: string
  value: string
}

interface SoftphoneProps {
  token: string
  activeCall?: CallSession
  onRegistered: () => void
  onPlatformUpdate: () => void
}

interface PeerSession {
  sessionDescriptionHandler?: { peerConnection?: RTCPeerConnection }
}

function qualityFrom(rtt?: number, jitter?: number, lost?: number): AgentMediaStatus['network_quality'] {
  if (rtt === undefined && jitter === undefined && lost === undefined) return 'unknown'
  if ((rtt || 0) > 450 || (jitter || 0) > 80 || (lost || 0) > 20) return 'poor'
  if ((rtt || 0) > 250 || (jitter || 0) > 40 || (lost || 0) > 5) return 'fair'
  return 'good'
}

export function WebRtcSoftphone({ token, activeCall, onRegistered, onPlatformUpdate }: SoftphoneProps) {
  const remoteAudioRef = useRef<HTMLAudioElement | null>(null)
  const simpleUserRef = useRef<SimpleUser | null>(null)
  const mountedRef = useRef(true)
  const [config, setConfig] = useState<WebRtcSessionConfig | null>(null)
  const [registration, setRegistration] = useState<AgentMediaStatus['registration_state']>('disconnected')
  const [mediaState, setMediaState] = useState<AgentMediaStatus['media_state']>('idle')
  const [permission, setPermission] = useState<AgentMediaStatus['microphone_permission']>('unknown')
  const [inputs, setInputs] = useState<DeviceOption[]>([])
  const [outputs, setOutputs] = useState<DeviceOption[]>([])
  const [inputDevice, setInputDevice] = useState(() => localStorage.getItem('agent-input-device') || '')
  const [outputDevice, setOutputDevice] = useState(() => localStorage.getItem('agent-output-device') || '')
  const [muted, setMuted] = useState(false)
  const [held, setHeld] = useState(false)
  const [incoming, setIncoming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [lastError, setLastError] = useState('')
  const [network, setNetwork] = useState<Pick<AgentMediaStatus, 'network_quality' | 'round_trip_time_ms' | 'jitter_ms' | 'packets_lost'>>({ network_quality: 'unknown' })
  const [connectedAt, setConnectedAt] = useState<number | null>(null)
  const [elapsed, setElapsed] = useState(0)

  const speech = useQuery({
    queryKey: ['softphone-speech', activeCall?.id],
    queryFn: () => apiRequest<SpeechTurn[]>(`/api/v1/calls/${activeCall?.id}/speech-turns?final_only=true`, {}, token),
    enabled: Boolean(activeCall?.id),
    refetchInterval: mediaState === 'active' ? 2000 : false,
  })
  const analysis = useQuery({
    queryKey: ['softphone-analysis', activeCall?.id],
    queryFn: () => apiRequest<CallAnalysis>(`/api/v1/calls/${activeCall?.id}/analysis`, {}, token),
    enabled: Boolean(activeCall?.id),
    refetchInterval: mediaState === 'active' ? 5000 : false,
  })

  const postStatus = useCallback(async (patch: Partial<AgentMediaStatus> = {}) => {
    const payload: AgentMediaStatus = {
      registration_state: registration,
      media_state: mediaState,
      microphone_permission: permission,
      input_device_id: inputDevice,
      output_device_id: outputDevice,
      active_call_id: activeCall?.id,
      muted,
      held,
      network_quality: network.network_quality,
      round_trip_time_ms: network.round_trip_time_ms,
      jitter_ms: network.jitter_ms,
      packets_lost: network.packets_lost,
      last_error: lastError,
      ...patch,
    }
    try {
      await apiRequest('/api/v1/agent/media/status', { method: 'PUT', body: JSON.stringify(payload) }, token)
    } catch {
      // The next heartbeat retries; media must keep working during a control-plane blip.
    }
  }, [activeCall?.id, held, inputDevice, lastError, mediaState, muted, network, outputDevice, permission, registration, token])

  const enumerateDevices = useCallback(async () => {
    if (!navigator.mediaDevices?.enumerateDevices) {
      setPermission('unavailable')
      return
    }
    const devices = await navigator.mediaDevices.enumerateDevices()
    setInputs(devices.filter((item) => item.kind === 'audioinput').map((item, index) => ({ value: item.deviceId, label: item.label || `麦克风 ${index + 1}` })))
    setOutputs(devices.filter((item) => item.kind === 'audiooutput').map((item, index) => ({ value: item.deviceId, label: item.label || `扬声器 ${index + 1}` })))
  }, [])

  const disconnect = useCallback(async () => {
    const phone = simpleUserRef.current
    simpleUserRef.current = null
    if (phone) {
      try { await phone.unregister() } catch { /* already disconnected */ }
      try { await phone.disconnect() } catch { /* already disconnected */ }
    }
    if (mountedRef.current) {
      setRegistration('disconnected')
      setMediaState('idle')
      setIncoming(false)
      setConnectedAt(null)
    }
    await postStatus({ registration_state: 'disconnected', media_state: 'idle', active_call_id: undefined })
  }, [postStatus])

  const connect = useCallback(async () => {
    setBusy(true)
    setLastError('')
    setRegistration('connecting')
    try {
      if (config && !config.enabled) throw new Error('服务器尚未启用浏览器软电话')
      if (!window.isSecureContext && location.hostname !== 'localhost' && location.hostname !== '127.0.0.1') {
        throw new Error('浏览器麦克风要求 HTTPS 安全环境')
      }
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('当前浏览器不支持麦克风访问')
      const probe = await navigator.mediaDevices.getUserMedia({ audio: inputDevice ? { deviceId: { exact: inputDevice } } : true, video: false })
      probe.getTracks().forEach((track) => track.stop())
      setPermission('granted')
      if ('Notification' in window && Notification.permission === 'default') {
        void Notification.requestPermission()
      }
      await enumerateDevices()
      const session = await apiRequest<WebRtcSessionConfig>('/api/v1/agent/webrtc/session', { method: 'POST' }, token)
      setConfig(session)
      if (!session.enabled) throw new Error('服务器尚未启用浏览器软电话')
      const delegate: SimpleUserDelegate = {
        onServerConnect: () => setRegistration('connecting'),
        onServerDisconnect: (error) => {
          setRegistration('disconnected')
          setLastError(error?.message || 'SIP WSS连接已断开')
          void postStatus({ registration_state: 'disconnected', last_error: error?.message || 'SIP WSS disconnected' })
        },
        onRegistered: () => {
          setRegistration('registered')
          setLastError('')
          void postStatus({ registration_state: 'registered', microphone_permission: 'granted', last_error: '' })
            .then(() => onRegistered())
        },
        onUnregistered: () => setRegistration('disconnected'),
        onCallCreated: () => setMediaState('connecting'),
        onCallReceived: () => {
          setIncoming(true)
          setMediaState('ringing')
          if ('Notification' in window && Notification.permission === 'granted') new Notification('AI外呼系统：转人工来电')
          void postStatus({ media_state: 'ringing' })
        },
        onCallAnswered: () => {
          setIncoming(false)
          setMediaState('active')
          setConnectedAt(Date.now())
          void postStatus({ media_state: 'active' })
        },
        onCallHold: (value) => {
          setHeld(value)
          setMediaState(value ? 'held' : 'active')
          void postStatus({ held: value, media_state: value ? 'held' : 'active' })
        },
        onCallHangup: () => {
          setIncoming(false)
          setMediaState('ended')
          setMuted(false)
          setHeld(false)
          setConnectedAt(null)
          void postStatus({ media_state: 'ended', muted: false, held: false, active_call_id: undefined })
          onPlatformUpdate()
        },
      }
      const audioConstraint = inputDevice ? { deviceId: { exact: inputDevice }, echoCancellation: true, noiseSuppression: true, autoGainControl: true } : true
      const options: SimpleUserOptions = {
        aor: session.sip_uri,
        delegate,
        reconnectionAttempts: 5,
        reconnectionDelay: 3,
        sendDTMFUsingSessionDescriptionHandler: true,
        media: {
          constraints: { audio: audioConstraint, video: false } as unknown as { audio: boolean; video: boolean },
          remote: { audio: remoteAudioRef.current || undefined },
        },
        userAgentOptions: {
          authorizationUsername: session.authorization_username,
          authorizationPassword: session.authorization_password,
          displayName: session.extension,
          sessionDescriptionHandlerFactoryOptions: {
            peerConnectionConfiguration: { iceServers: session.ice_servers },
          },
        },
      }
      await disconnect()
      const phone = new SimpleUser(session.wss_url, options)
      simpleUserRef.current = phone
      await phone.connect()
      await phone.register()
    } catch (error) {
      const text = error instanceof Error ? error.message : '软电话连接失败'
      setRegistration('error')
      setMediaState('error')
      setLastError(text)
      if (String(text).toLowerCase().includes('permission') || String(text).includes('denied')) setPermission('denied')
      message.error(text)
      void postStatus({ registration_state: 'error', media_state: 'error', last_error: text })
    } finally {
      setBusy(false)
    }
  }, [config, disconnect, enumerateDevices, inputDevice, onPlatformUpdate, onRegistered, postStatus, token])

  const answer = async () => {
    try {
      setBusy(true)
      await simpleUserRef.current?.answer()
    } catch (error) { message.error(error instanceof Error ? error.message : '接听失败') } finally { setBusy(false) }
  }
  const decline = async () => {
    try { await simpleUserRef.current?.decline(); setIncoming(false); setMediaState('idle') } catch (error) { message.error(error instanceof Error ? error.message : '拒接失败') }
  }
  const hangup = async () => {
    try {
      await simpleUserRef.current?.hangup()
      if (activeCall?.id) await apiRequest(`/api/v1/calls/${activeCall.id}/hangup?reason=agent_browser`, { method: 'POST' }, token)
    } catch (error) { message.error(error instanceof Error ? error.message : '挂机失败') }
  }
  const toggleMute = () => {
    const phone = simpleUserRef.current
    if (!phone) return
    if (muted) phone.unmute(); else phone.mute()
    setMuted(!muted)
  }
  const toggleHold = async () => {
    try { if (held) await simpleUserRef.current?.unhold(); else await simpleUserRef.current?.hold() } catch (error) { message.error(error instanceof Error ? error.message : '保持操作失败') }
  }

  useEffect(() => {
    mountedRef.current = true
    void apiRequest<WebRtcSessionConfig>('/api/v1/agent/webrtc/session', { method: 'POST' }, token).then(setConfig).catch(() => setConfig(null))
    return () => { mountedRef.current = false; void disconnect() }
  }, [token])

  useEffect(() => {
    const audio = remoteAudioRef.current as (HTMLAudioElement & { setSinkId?: (deviceId: string) => Promise<void> }) | null
    if (audio?.setSinkId && outputDevice) void audio.setSinkId(outputDevice).catch(() => message.warning('无法切换扬声器设备'))
    if (outputDevice) localStorage.setItem('agent-output-device', outputDevice)
  }, [outputDevice])

  useEffect(() => {
    if (inputDevice) localStorage.setItem('agent-input-device', inputDevice)
  }, [inputDevice])

  useEffect(() => {
    const handler = () => void enumerateDevices()
    navigator.mediaDevices?.addEventListener('devicechange', handler)
    return () => navigator.mediaDevices?.removeEventListener('devicechange', handler)
  }, [enumerateDevices])

  useEffect(() => {
    const interval = window.setInterval(() => void postStatus(), 15_000)
    return () => window.clearInterval(interval)
  }, [postStatus])

  useEffect(() => {
    if (!config?.expires_at || registration !== 'registered') return
    const refreshInMs = Math.max(30_000, new Date(config.expires_at).getTime() - Date.now() - 60_000)
    const timer = window.setTimeout(() => void connect(), refreshInMs)
    return () => window.clearTimeout(timer)
  }, [config?.expires_at, connect, registration])

  useEffect(() => {
    if (!connectedAt) { setElapsed(0); return }
    const update = () => setElapsed(Math.max(0, Math.floor((Date.now() - connectedAt) / 1000)))
    update()
    const timer = window.setInterval(update, 1000)
    return () => window.clearInterval(timer)
  }, [connectedAt])

  useEffect(() => {
    const collect = async () => {
      const raw = simpleUserRef.current as unknown as { session?: PeerSession }
      const peer = raw?.session?.sessionDescriptionHandler?.peerConnection
      if (!peer) return
      const stats = await peer.getStats()
      let rtt: number | undefined
      let jitter: number | undefined
      let lost: number | undefined
      stats.forEach((report) => {
        if (report.type === 'candidate-pair' && report.state === 'succeeded' && report.currentRoundTripTime !== undefined) rtt = Number(report.currentRoundTripTime) * 1000
        if (report.type === 'inbound-rtp' && report.kind === 'audio') {
          if (report.jitter !== undefined) jitter = Number(report.jitter) * 1000
          if (report.packetsLost !== undefined) lost = Number(report.packetsLost)
        }
      })
      setNetwork({ network_quality: qualityFrom(rtt, jitter, lost), round_trip_time_ms: rtt, jitter_ms: jitter, packets_lost: lost })
    }
    const interval = window.setInterval(() => void collect(), 5000)
    return () => window.clearInterval(interval)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    const run = async () => {
      while (!controller.signal.aborted) {
        try {
          const response = await fetch('/api/v1/agent/events/stream', { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal })
          if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`)
          const reader = response.body.getReader()
          const decoder = new TextDecoder()
          let buffer = ''
          while (!controller.signal.aborted) {
            const { value, done } = await reader.read()
            if (done) break
            buffer += decoder.decode(value, { stream: true })
            const lines = buffer.split('\n')
            buffer = lines.pop() || ''
            if (lines.some(Boolean)) onPlatformUpdate()
          }
        } catch {
          if (!controller.signal.aborted) await new Promise((resolve) => window.setTimeout(resolve, 2000))
        }
      }
    }
    void run()
    return () => controller.abort()
  }, [onPlatformUpdate, token])

  const statusColor = registration === 'registered' ? 'success' : registration === 'connecting' ? 'processing' : registration === 'error' ? 'error' : 'default'
  const latestTranscript = useMemo(() => speech.data?.slice(-3) || [], [speech.data])
  const clock = `${String(Math.floor(elapsed / 60)).padStart(2, '0')}:${String(elapsed % 60).padStart(2, '0')}`

  return (
    <Card className="softphone-card" title={<Space><PhoneOutlined /><span>浏览器软电话</span><Tag color={statusColor}>{registration}</Tag></Space>} extra={<Button icon={<ReloadOutlined />} loading={busy} disabled={!config?.enabled} onClick={() => void connect()}>{registration === 'registered' ? '重新注册' : '启用软电话'}</Button>}>
      <audio ref={remoteAudioRef} autoPlay playsInline />
      {!config?.enabled && <Alert type="warning" showIcon message="服务器尚未启用WebRTC" description="需要配置FreeSWITCH WSS、SIP域名和coturn后才能真实接听。" style={{ marginBottom: 12 }} />}
      {lastError && <Alert type="error" showIcon closable message={lastError} onClose={() => setLastError('')} style={{ marginBottom: 12 }} />}
      <Descriptions size="small" column={2} items={[
        { key: 'extension', label: '分机', children: config?.extension || '-' },
        { key: 'permission', label: '麦克风权限', children: permission },
        { key: 'media', label: '通话状态', children: mediaState },
        { key: 'network', label: '网络质量', children: <Tag color={network.network_quality === 'good' ? 'success' : network.network_quality === 'fair' ? 'warning' : network.network_quality === 'poor' ? 'error' : 'default'}>{network.network_quality}</Tag> },
      ]} />
      <Space direction="vertical" className="full-width" style={{ marginTop: 12 }}>
        <Select className="full-width" value={inputDevice || undefined} placeholder="选择麦克风" options={inputs} disabled={!config?.enabled} onChange={setInputDevice} />
        <Select className="full-width" value={outputDevice || undefined} placeholder="选择扬声器" options={outputs} disabled={!config?.enabled} onChange={setOutputDevice} />
      </Space>
      {incoming && <div className="incoming-call-panel"><Title level={4}>转人工来电</Title><Text>{activeCall?.phone || '客户来电'}</Text><Space><Button type="primary" size="large" onClick={() => void answer()} loading={busy}>接听</Button><Button danger size="large" onClick={() => void decline()}>拒绝</Button></Space></div>}
      {['active', 'held'].includes(mediaState) && <>
        <Divider />
        <div className="active-call-head"><div><Text type="secondary">当前客户</Text><Title level={4}>{activeCall?.phone || '已接通'}</Title></div><Tag color="processing">{clock}</Tag></div>
        <Space wrap>
          <Button icon={muted ? <AudioOutlined /> : <AudioMutedOutlined />} onClick={toggleMute}>{muted ? '取消静音' : '静音'}</Button>
          <Button icon={held ? <PlayCircleOutlined /> : <PauseCircleOutlined />} onClick={() => void toggleHold()}>{held ? '恢复' : '保持'}</Button>
          <Button danger icon={<DisconnectOutlined />} onClick={() => void hangup()}>挂机</Button>
        </Space>
        <div className="dtmf-grid">{DTMF_TONES.map((tone) => <Button key={tone} onClick={() => void simpleUserRef.current?.sendDTMF(tone)}>{tone}</Button>)}</div>
        <Divider />
        <Text strong>实时转写与AI提示</Text>
        <div className="transcript-panel">
          {latestTranscript.length ? latestTranscript.map((turn) => <div key={turn.id}><Text type="secondary">{turn.speaker_role}：</Text>{turn.transcript}</div>) : <Text type="secondary">等待转写数据</Text>}
          {analysis.data?.summary && <><Divider /><Text strong>摘要：</Text><Text>{analysis.data.summary}</Text></>}
        </div>
      </>}
    </Card>
  )
}
