import { api, ApiError } from '@/lib/api'

/** Public API helper that sends X-Gym-Slug so the backend resolves the correct tenant. */
export async function publicApi<T>(slug: string, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('X-Gym-Slug', slug)
  try {
    return await api<T>(path, { ...init, headers })
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw e
  }
}
