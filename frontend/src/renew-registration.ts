import type { WebRtcSessionConfig } from './types'

/** Refresh credentials and re-REGISTER without disposing the current SIP dialog. */
export async function renewRegistration(
  fetchConfig: () => Promise<WebRtcSessionConfig>,
  getCurrent: () => { config: WebRtcSessionConfig | null; phone: { register: () => Promise<void> } | null },
): Promise<WebRtcSessionConfig> {
  const next = await fetchConfig()
  const { config, phone } = getCurrent()
  if (!phone) return next
  if (!next.enabled || config?.authorization_password !== next.authorization_password ||
      config?.wss_url !== next.wss_url || config?.sip_uri !== next.sip_uri) {
    throw new Error('注册配置已变化，请在通话结束后重新注册')
  }
  await phone.register()
  return next
}
