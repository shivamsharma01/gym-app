import { Button } from '@/components/ui'
import { ApiError } from '@/lib/api'

export function QueryError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry?: () => void
}) {
  const message = error instanceof ApiError ? error.message : 'Something went wrong'
  return (
    <div
      className="rounded-2xl border border-danger/25 bg-danger/8 px-4 py-3 text-sm text-danger"
      role="alert"
    >
      <div className="font-semibold">Couldn’t load this data</div>
      <p className="mt-1 text-danger/90">{message}</p>
      {onRetry ? (
        <Button type="button" variant="outline" size="sm" className="mt-3 border-danger/30 text-danger hover:bg-danger/10" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  )
}
