import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { loginRequest, logoutRequest, refreshRequest, setRefreshHandler } from '@/lib/api'
import { setTokens } from '@/lib/tokens'
import type { UserSummary } from '@/lib/types'

type AuthContextValue = {
  user: UserSummary | null
  login: (usernameOrEmail: string, password: string) => Promise<void>
  logout: () => Promise<void>
  has: (permission: string) => boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)

  useEffect(() => {
    setRefreshHandler(async () => {
      const ok = await refreshRequest()
      if (!ok) setUser(null)
      return ok
    })
    return () => setRefreshHandler(null)
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      has: (permission) => Boolean(user?.permissions.includes(permission)),
      login: async (usernameOrEmail, password) => {
        const tokens = await loginRequest(usernameOrEmail, password)
        setTokens(tokens.accessToken, tokens.refreshToken)
        setUser(tokens.user)
      },
      logout: async () => {
        await logoutRequest()
        setUser(null)
      },
    }),
    [user],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
