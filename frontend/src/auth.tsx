import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { apiRequest } from './api'
import type { LoginResponse, Role, User } from './types'

const TOKEN_KEY = 'ai-platform-access-token'

interface AuthContextValue {
  user: User | null
  token: string | null
  loading: boolean
  login: (username: string, password: string, expectedRole: Role) => Promise<User>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(TOKEN_KEY))
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(Boolean(token))

  useEffect(() => {
    if (!token) {
      setLoading(false)
      return
    }
    apiRequest<User>('/api/v1/auth/me', {}, token)
      .then(setUser)
      .catch(() => {
        sessionStorage.removeItem(TOKEN_KEY)
        setToken(null)
      })
      .finally(() => setLoading(false))
  }, [token])

  const value = useMemo<AuthContextValue>(() => ({
    user,
    token,
    loading,
    login: async (username, password, expectedRole) => {
      const response = await apiRequest<LoginResponse>('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      })
      if (response.role !== expectedRole && !(expectedRole === 'agent' && response.role === 'admin')) {
        throw new Error(expectedRole === 'admin' ? 'This account is not an administrator' : 'This account cannot access the agent portal')
      }
      sessionStorage.setItem(TOKEN_KEY, response.access_token)
      setToken(response.access_token)
      const profile = await apiRequest<User>('/api/v1/auth/me', {}, response.access_token)
      setUser(profile)
      return profile
    },
    logout: () => {
      sessionStorage.removeItem(TOKEN_KEY)
      setToken(null)
      setUser(null)
    },
  }), [loading, token, user])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used within AuthProvider')
  return value
}
