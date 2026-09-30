import { apiUrl } from '@/lib/backendUrls'
import { clearTokens, getAccessToken, setAccessToken } from '@/lib/tokens'
import type { TokenResponse } from '@/lib/types'

export class ApiError extends Error {
  status: number
  code?: string
  /** Per-field problems from a validation failure, already readable (e.g. "Password: size must be …"). */
  errors: string[]

  constructor(status: number, message: string, code?: string, errors: string[] = []) {
    super(errors.length ? errors.join(' · ') : message)
    this.status = status
    this.code = code
    this.errors = errors
  }
}

/** "fullName: must not be blank" → "Full name: must not be blank". */
function readableError(raw: string) {
  const split = raw.indexOf(': ')
  if (split < 0) return raw
  const field = raw
    .slice(0, split)
    .replace(/\[\d+\]/g, '')
    .split('.')
    .pop()!
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
  return `${field.charAt(0).toUpperCase()}${field.slice(1)}: ${raw.slice(split + 2)}`
}

type RefreshHandler = () => Promise<boolean>
let refreshHandler: RefreshHandler | null = null

export function setRefreshHandler(handler: RefreshHandler | null) {
  refreshHandler = handler
}

async function send(path: string, init: RequestInit, retried: boolean): Promise<Response> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getAccessToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const res = await fetch(apiUrl(path), { ...init, headers, credentials: 'include' })
  if (res.status === 401 && !retried && !path.includes('/auth/login') && !path.includes('/auth/refresh')) {
    const ok = refreshHandler ? await refreshHandler() : false
    if (ok) {
      return send(path, init, true)
    }
  }
  return res
}

/** Authenticated binary GET (e.g. a member photo). Resolves to null on 404. */
export async function apiBlob(path: string): Promise<Blob | null> {
  const res = await send(path, {}, false)
  if (res.status === 404) return null
  if (!res.ok) throw new ApiError(res.status, res.statusText)
  return res.blob()
}

export async function api<T>(path: string, init: RequestInit = {}, retried = false): Promise<T> {
  const res = await send(path, init, retried)

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
    const errors = Array.isArray(json.errors)
      ? json.errors.filter((e): e is string => typeof e === 'string').map(readableError)
      : []
    throw new ApiError(res.status, detail, json.code as string | undefined, errors)
  }
  return json as T
}

export async function loginRequest(usernameOrEmail: string, password: string) {
  return api<TokenResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ usernameOrEmail, password }),
  })
}

/** Shared in-flight refresh so StrictMode double-mount / 401 retries cannot rotate twice. */
let refreshInFlight: Promise<boolean> | null = null

export async function refreshRequest() {
  if (refreshInFlight) return refreshInFlight
  refreshInFlight = (async () => {
    try {
      const tokens = await api<TokenResponse>('/api/v1/auth/refresh', {
        method: 'POST',
        body: '{}',
      })
      setAccessToken(tokens.accessToken)
      return true
    } catch {
      clearTokens()
      return false
    } finally {
      refreshInFlight = null
    }
  })()
  return refreshInFlight
}

export async function logoutRequest() {
  try {
    await api('/api/v1/auth/logout', {
      method: 'POST',
      body: '{}',
    })
  } catch {
    // still clear local session
  }
  clearTokens()
}
