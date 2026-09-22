import { apiUrl } from '@/lib/backendUrls'
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from '@/lib/tokens'
import type { TokenResponse } from '@/lib/types'

export class ApiError extends Error {
  status: number
  code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

type RefreshHandler = () => Promise<boolean>
let refreshHandler: RefreshHandler | null = null

export function setRefreshHandler(handler: RefreshHandler | null) {
  refreshHandler = handler
}

export async function api<T>(path: string, init: RequestInit = {}, retried = false): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getAccessToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(apiUrl(path), { ...init, headers })
  if (res.status === 401 && !retried && !path.includes('/auth/login') && !path.includes('/auth/refresh')) {
    const ok = refreshHandler ? await refreshHandler() : false
    if (ok) {
      return api<T>(path, init, true)
    }
  }

  if (res.status === 204) {
    return undefined as T
  }

  const text = await res.text()
  let json: Record<string, unknown> = {}
  if (text) {
    try {
      json = JSON.parse(text) as Record<string, unknown>
    } catch {
      json = { detail: text }
    }
  }
  if (!res.ok) {
    const detail = (json.detail as string) || (json.title as string) || res.statusText
    throw new ApiError(res.status, detail, json.code as string | undefined)
  }
  return json as T
}

export async function loginRequest(usernameOrEmail: string, password: string) {
  return api<TokenResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ usernameOrEmail, password }),
  })
}

export async function refreshRequest() {
  const refresh = getRefreshToken()
  if (!refresh) return false
  try {
    const tokens = await api<TokenResponse>('/api/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: refresh }),
    })
    setTokens(tokens.accessToken, tokens.refreshToken)
    return true
  } catch {
    clearTokens()
    return false
  }
}

export async function logoutRequest() {
  const refresh = getRefreshToken()
  if (refresh) {
    try {
      await api('/api/v1/auth/logout', {
        method: 'POST',
        body: JSON.stringify({ refreshToken: refresh }),
      })
    } catch {
      // still clear local session
    }
  }
  clearTokens()
}
