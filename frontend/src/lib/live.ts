import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { refreshRequest } from '@/lib/api'
import { useAuth } from '@/lib/useAuth'
import { staffLiveWsProtocols, staffLiveWsUrl } from '@/lib/backendUrls'
import { getAccessToken } from '@/lib/tokens'

export type LiveState = 'off' | 'live' | 'reconnecting' | 'offline'

const BASE_RECONNECT_MS = 5_000
const MAX_RECONNECT_MS = 5 * 60_000
const FAIL_BEFORE_OFFLINE = 3

export function useStaffLive() {
  const { user } = useAuth()
  const qc = useQueryClient()
  const [connection, setConnection] = useState<Exclude<LiveState, 'off'>>('reconnecting')
  const failCount = useRef(0)
  const intentionalClose = useRef(false)
  const authRejected = useRef(false)
  const canConnect = Boolean(user && getAccessToken())

  useEffect(() => {
    if (!canConnect) {
      return
    }

    let ws: WebSocket | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let cancelled = false
    let attempt = 0

    function clearTimer() {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
    }

    function backoffMs() {
      const exp = Math.min(MAX_RECONNECT_MS, BASE_RECONNECT_MS * 2 ** Math.min(attempt, 6))
      return exp + Math.floor(Math.random() * 1000)
    }

    function scheduleReconnect() {
      clearTimer()
      if (cancelled || authRejected.current) return
      reconnectTimer = setTimeout(() => {
        void connect()
      }, backoffMs())
    }

    async function connect() {
      if (cancelled || authRejected.current) return
      let token = getAccessToken()
      if (!user || !token) {
        return
      }

      // Access JWT is short-lived; refresh before opening so we do not hammer /live with an expired token.
      if (tokenExpiringSoon(token)) {
        const ok = await refreshRequest()
        if (!ok) {
          authRejected.current = true
          setConnection('offline')
          return
        }
        token = getAccessToken()
        if (!token) {
          authRejected.current = true
          setConnection('offline')
          return
        }
      }

      intentionalClose.current = false
      ws = new WebSocket(staffLiveWsUrl(), staffLiveWsProtocols(token))

      ws.onopen = () => {
        failCount.current = 0
        attempt = 0
        authRejected.current = false
        setConnection('live')
      }

      ws.onmessage = (event: MessageEvent) => {
        let message: { type?: string; payload?: { memberId?: string } } = {}
        try {
          message = JSON.parse(String(event.data)) as typeof message
        } catch {
          // non-JSON frames still refresh the general views below
        }
        if (message.type === 'MEMBER_SYNC') {
          const memberId = message.payload?.memberId
          if (memberId) {
            void qc.invalidateQueries({ queryKey: ['member-device-sync', memberId] })
            void qc.invalidateQueries({ queryKey: ['member-photo', memberId] })
            void qc.invalidateQueries({ queryKey: ['member', memberId] })
          }
          void qc.invalidateQueries({ queryKey: ['members'] })
          void qc.invalidateQueries({ queryKey: ['sync-commands'] })
          return
        }
        if (message.type === 'ATTENDANCE_LINKED') {
          void qc.invalidateQueries({ queryKey: ['attendance'] })
          void qc.invalidateQueries({ queryKey: ['member-attendance'] })
          return
        }
        void qc.invalidateQueries({ queryKey: ['attendance'] })
        void qc.invalidateQueries({ queryKey: ['devices'] })
        void qc.invalidateQueries({ queryKey: ['device'] })
        void qc.invalidateQueries({ queryKey: ['gateways'] })
        void qc.invalidateQueries({ queryKey: ['security-events'] })
        void qc.invalidateQueries({ queryKey: ['device-health'] })
      }

      ws.onerror = () => {
        // onclose follows; treat as failure path there
      }

      ws.onclose = (event) => {
        if (cancelled || intentionalClose.current) return
        // 1008 = policy violation (auth); 4401/4403 used by some proxies for unauthorized.
        const looksLikeAuth =
          event.code === 1008 || event.code === 4401 || event.code === 4403 || event.code === 4001
        failCount.current += 1
        attempt += 1
        if (failCount.current >= FAIL_BEFORE_OFFLINE) {
          setConnection('offline')
        } else {
          setConnection('reconnecting')
        }
        if (looksLikeAuth) {
          void (async () => {
            const ok = await refreshRequest()
            if (!ok) {
              authRejected.current = true
              setConnection('offline')
              return
            }
            scheduleReconnect()
          })()
          return
        }
        scheduleReconnect()
      }
    }

    void connect()

    return () => {
      cancelled = true
      intentionalClose.current = true
      clearTimer()
      if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
        ws.close()
      }
    }
  }, [canConnect, user, qc])

  return canConnect ? connection : 'off'
}

/** Refresh when under 2 minutes remain, or when the JWT cannot be read. */
function tokenExpiringSoon(token: string, skewSeconds = 120): boolean {
  const exp = jwtExpSeconds(token)
  if (exp == null) return true
  return exp * 1000 <= Date.now() + skewSeconds * 1000
}

function jwtExpSeconds(token: string): number | null {
  const parts = token.split('.')
  if (parts.length < 2) return null
  try {
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: unknown }
    return typeof payload.exp === 'number' ? payload.exp : null
  } catch {
    return null
  }
}
