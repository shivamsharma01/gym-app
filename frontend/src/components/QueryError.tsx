import { Button } from '@/components/ui'
import { ApiError } from '@/lib/api'

export function QueryError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry?: () => void
}) {
  const fieldErrors = error instanceof ApiError ? error.errors : []
  const message =
    error instanceof Error ? error.message : 'Something went wrong. Please try again.'

  return (
    <div className="rounded-xl border border-danger/30 bg-danger/10 px-5 py-4 text-danger">
      <div className="font-semibold">
        {fieldErrors.length ? 'Please fix the following' : 'Unable to complete the request'}
      </div>
      {fieldErrors.length ? (
        <ul className="mt-1 list-disc space-y-0.5 pl-5 text-sm text-danger/90">
          {fieldErrors.map((e) => (
            <li key={e}>{e}</li>
          ))}
        </ul>
      ) : (
        <div className="mt-1 text-sm text-danger/90">{message}</div>
      )}
      {onRetry ? (
        <Button type="button" variant="outline" size="sm" className="mt-3" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  )
}
