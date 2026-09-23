import { Button } from '@/components/ui'

export function QueryError({
  error,
  onRetry,
}: {
  error: unknown
  onRetry?: () => void
}) {
  const message =
    error instanceof Error ? error.message : 'Something went wrong. Please try again.'

  return (
    <div className="rounded-xl border border-danger/30 bg-danger/10 px-5 py-4 text-danger">
      <div className="font-semibold">Unable to complete the request</div>
      <div className="mt-1 text-sm text-danger/90">{message}</div>
      {onRetry ? (
        <Button type="button" variant="outline" size="sm" className="mt-3" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  )
}
