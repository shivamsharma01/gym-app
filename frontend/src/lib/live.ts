import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useAuth } from '@/lib/auth'
import { getAccessToken } from '@/lib/tokens'

export function useStaffLive() {
  const { user } = useAuth()
  const qc = useQueryClient()
  const [state, setState] = useState<'off' | 'live' | 'down'>('off')

  useEffect(() => {
    const token = getAccessToken()
    if (!user || !token) {
      setState('off')
      return
    }
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${window.location.host}/live?access_token=${encodeURIComponent(token)}`)
    ws.onopen = () => setState('live')
    ws.onclose = () => setState('down')
    ws.onerror = () => setState('down')
    ws.onmessage = () => {
      void qc.invalidateQueries({ queryKey: ['attendance'] })
      void qc.invalidateQueries({ queryKey: ['devices'] })
      void qc.invalidateQueries({ queryKey: ['security-events'] })
      void qc.invalidateQueries({ queryKey: ['device-health'] })
    }
    return () => ws.close()
  }, [user, qc])

  return state
}
