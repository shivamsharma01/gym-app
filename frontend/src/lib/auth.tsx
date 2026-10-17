import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, loginRequest, logoutRequest, refreshRequest, setRefreshHandler } from '@/lib/api'
import { AuthContext } from '@/lib/auth-context'
import { setAccessToken } from '@/lib/tokens'
import type { UserSummary } from '@/lib/types'

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

  const value = useMemo(
    () => ({
      user,
      ready,
      has: (permission: string) => Boolean(user?.permissions.includes(permission)),
      login: async (usernameOrEmail: string, password: string) => {
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
