import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, loginRequest, logoutRequest, refreshRequest, setRefreshHandler } from '@/lib/api'
import { setAccessToken } from '@/lib/tokens'
import type { UserSummary } from '@/lib/types'

type AuthContextValue = {
  user: UserSummary | null
  ready: boolean
  login: (usernameOrEmail: string, password: string) => Promise<UserSummary>
  logout: () => Promise<void>
  has: (permission: string) => boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    setRefreshHandler(async () => {
      const ok = await refreshRequest()
      if (!ok) setUser(null)
      return ok
    })
    return () => setRefreshHandler(null)
  }, [])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const ok = await refreshRequest()
        if (!ok || cancelled) return
        const me = await api<UserSummary>('/api/v1/me')
        if (!cancelled) setUser(me)
      } catch {
        if (!cancelled) setUser(null)
      } finally {
        if (!cancelled) setReady(true)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      ready,
      has: (permission) => Boolean(user?.permissions.includes(permission)),
      login: async (usernameOrEmail, password) => {
        const tokens = await loginRequest(usernameOrEmail, password)
        setAccessToken(tokens.accessToken)
        setUser(tokens.user)
        return tokens.user
      },
      logout: async () => {
        await logoutRequest()
        setUser(null)
      },
    }),
    [user, ready],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
