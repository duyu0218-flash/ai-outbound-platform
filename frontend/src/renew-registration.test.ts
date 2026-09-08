import { describe, expect, it, vi } from 'vitest'
import { renewRegistration } from './renew-registration'
import type { WebRtcSessionConfig } from './types'

const config = { enabled: true, authorization_password: 'test', sip_uri: 'sip:1@test', wss_url: 'wss://test' } as WebRtcSessionConfig

describe('SIP credential renewal', () => {
  it('preserves an active or incoming dialog while refreshing REGISTER', async () => {
    const dialog = { state: 'active' }
    const phone = { session: dialog, register: vi.fn().mockResolvedValue(undefined), disconnect: vi.fn(), unregister: vi.fn() }
    const next = { ...config, expires_at: '2099-01-01T00:00:00Z' }
    await renewRegistration(async () => next, () => ({ config, phone }))
    expect(phone.session).toBe(dialog)
    expect(phone.register).toHaveBeenCalledOnce()
    expect(phone.disconnect).not.toHaveBeenCalled()
    expect(phone.unregister).not.toHaveBeenCalled()
  })
  it('leaves the call alive on changed credentials and on control-plane failure', async () => {
    const phone = { register: vi.fn(), disconnect: vi.fn() }
    await expect(renewRegistration(async () => ({ ...config, authorization_password: 'changed' }), () => ({ config, phone }))).rejects.toThrow('通话结束')
    await expect(renewRegistration(async () => { throw new Error('network') }, () => ({ config, phone }))).rejects.toThrow('network')
    expect(phone.register).not.toHaveBeenCalled()
    expect(phone.disconnect).not.toHaveBeenCalled()
  })
  it('does not revive a phone removed while renewal was in flight', async () => {
    let current: { config: WebRtcSessionConfig; phone: { register: ReturnType<typeof vi.fn> } | null } = { config, phone: { register: vi.fn() } }
    const oldPhone = current.phone!
    await renewRegistration(async () => { current = { config, phone: null }; return config }, () => current)
    expect(oldPhone.register).not.toHaveBeenCalled()
  })
})
