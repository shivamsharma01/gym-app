/** In-memory tokens only — never localStorage (ADR 0001). A full page reload requires login again. */
let accessToken: string | null = null
let refreshToken: string | null = null

export function getAccessToken() {
  return accessToken
}

export function getRefreshToken() {
  return refreshToken
}

export function setTokens(access: string | null, refresh: string | null) {
  accessToken = access
  refreshToken = refresh
}

export function clearTokens() {
  accessToken = null
  refreshToken = null
}
