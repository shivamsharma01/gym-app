import { ApiError } from '@/lib/api'

export function QueryError({ error }: { error: unknown }) {
  const message = error instanceof ApiError ? error.message : 'Something went wrong'
  return <p className="text-sm text-danger">{message}</p>
}
