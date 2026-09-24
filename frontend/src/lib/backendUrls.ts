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

/** Pure helper for tests and WebSocket construction. */
export function buildLiveWsUrl(
  location: Pick<Location, 'protocol' | 'host'>,
  accessToken: string,
): string {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = encodeURIComponent(accessToken)
  return `${protocol}//${location.host}/live?access_token=${token}`
}

/** Staff attendance live WebSocket — always derived from window.location. */
export function staffLiveWsUrl(accessToken: string): string {
  return buildLiveWsUrl(window.location, accessToken)
}
