/**
 * Same-origin backend URL helpers for multi-domain SaaS.
 *
 * Production: leave VITE_API_BASE unset so REST and Actuator use relative paths
 * (`/api/...`, `/actuator/...`) against the current browser origin.
 *
 * Local/dev: Vite proxies those paths to Spring Boot (see vite.config.ts).
 *
 * Optional VITE_API_BASE is only for rare tooling overrides — never bake a
 * customer API hostname into the production SPA build.
 *
 * /gateway is used by the Windows device gateway agent, not this React app.
 */

export function apiBase(): string {
  const env = (import.meta as ImportMeta & { env?: Record<string, string | undefined> }).env
  const override = env?.VITE_API_BASE?.trim()
  if (!override) return ''
  return override.replace(/\/$/, '')
}

/** Join API base (usually empty) with an absolute path starting with `/`. */
export function apiUrl(path: string): string {
  const p = path.startsWith('/') ? path : `/${path}`
  return `${apiBase()}${p}`
}

/**
 * Subprotocol echoed by the server. The JWT is a second protocol value because
 * the browser WebSocket API cannot set `Authorization`.
 */
export const STAFF_LIVE_PROTOCOL = 'bearer'

/** Pure helper for tests and WebSocket construction. Token stays out of the URL. */
export function buildLiveWsUrl(location: Pick<Location, 'protocol' | 'host'>): string {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${location.host}/live`
}

/** `new WebSocket(url, protocols)` — server echoes only {@link STAFF_LIVE_PROTOCOL}. */
export function staffLiveWsProtocols(accessToken: string): [string, string] {
  return [STAFF_LIVE_PROTOCOL, accessToken]
}

/** Staff attendance live WebSocket — always derived from window.location. */
export function staffLiveWsUrl(): string {
  return buildLiveWsUrl(window.location)
}
