import { createContext } from 'react'
import type { UserSummary } from '@/lib/types'

export type AuthContextValue = {
  user: UserSummary | null
  ready: boolean
  login: (usernameOrEmail: string, password: string) => Promise<UserSummary>
  logout: () => Promise<void>
  has: (permission: string) => boolean
}

export const AuthContext = createContext<AuthContextValue | null>(null)
