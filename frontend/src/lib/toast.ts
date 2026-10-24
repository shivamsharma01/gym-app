import { toast } from 'sonner'
import { ApiError, humanError, isHtml } from '@/lib/api'

/** Plain message for a failed action. Network drops stay short. */
export function toastError(error: unknown) {
  toast.error(plainError(error))
}

export function toastOk(message: string) {
  toast.success(message)
}

export function plainError(error: unknown) {
  if (error instanceof ApiError) {
    const text = error.errors[0] || error.message
    return clip(text || 'That did not work. Try again.')
  }
  if (error instanceof Error) {
    if (/fetch|network|load failed|failed to fetch/i.test(error.message)) {
      return 'Could not reach the server. Try again.'
    }
    if (error.message) return clip(error.message)
  }
  return 'That did not work. Try again.'
}

function clip(message: string) {
  if (isHtml(message)) return humanError(502, '', '')
  const clean = message.replace(/\s+/g, ' ').trim()
  return clean.length > 180 ? `${clean.slice(0, 177)}…` : clean
}
