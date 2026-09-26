import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useAuth } from '@/lib/auth'
import { staffLiveWsUrl } from '@/lib/backendUrls'
import { getAccessToken } from '@/lib/tokens'

export type LiveState = 'off' | 'live' | 'reconnecting' | 'offline'

const RECONNECT_MS = 15_000
const FAIL_BEFORE_OFFLINE = 3

export function useStaffLive() {
  const { user } = useAuth()
  const qc = useQueryClient()
  const [state, setState] = useState<LiveState>('off')
  const failCount = useRef(0)
  const intentionalClose = useRef(false)

  useEffect(() => {
    let ws: WebSocket | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null
    let cancelled = false

    function clearTimer() {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
    }

    function scheduleReconnect() {
      clearTimer()
      if (cancelled) return
      reconnectTimer = setTimeout(() => {
        connect()
      }, RECONNECT_MS)
    }

    function connect() {
      if (cancelled) return
      const token = getAccessToken()
      if (!user || !token) {
        setState('off')
        return
      }

      intentionalClose.current = false
      ws = new WebSocket(staffLiveWsUrl(token))

      ws.onopen = () => {
        failCount.current = 0
        setState('live')
      }

      ws.onmessage = () => {
        void qc.invalidateQueries({ queryKey: ['attendance'] })
        void qc.invalidateQueries({ queryKey: ['devices'] })
        void qc.invalidateQueries({ queryKey: ['security-events'] })
        void qc.invalidateQueries({ queryKey: ['device-health'] })
      }

      ws.onerror = () => {
        // onclose follows; treat as failure path there
      }

      ws.onclose = () => {
        if (cancelled || intentionalClose.current) return
        failCount.current += 1
        if (failCount.current >= FAIL_BEFORE_OFFLINE) {
          setState('offline')
        } else {
          setState('reconnecting')
        }
        scheduleReconnect()
      }
    }

    if (!user || !getAccessToken()) {
      setState('off')
      return
    }

    setState('reconnecting')
    connect()

    return () => {
      cancelled = true
      intentionalClose.current = true
      clearTimer()
      if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
        ws.close()
      }
    }
  }, [user, qc])

  return state
}
