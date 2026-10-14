/** In-memory access token only — refresh lives in an httpOnly cookie (ADR 0001). */
let accessToken: string | null = null

export function getAccessToken() {
  return accessToken
}

export function setAccessToken(access: string | null) {
  accessToken = access
}

export function clearTokens() {
  accessToken = null
}
