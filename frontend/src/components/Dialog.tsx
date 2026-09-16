import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Button } from '@/components/ui'
import { cn } from '@/lib/cn'

export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  className,
}: {
  open: boolean
  onClose: () => void
  title: string
  description?: string
  children: ReactNode
  className?: string
}) {
  const titleId = useId()
  const descId = useId()
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    panelRef.current?.focus()
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prev
    }
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center" role="presentation">
      <button
        type="button"
        aria-label="Close dialog"
        className="absolute inset-0 bg-black/65 animate-[overlay-in_0.16s_ease-out]"
        onClick={onClose}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className={cn(
          'relative z-10 w-full max-w-md rounded-2xl border border-line bg-panel p-5 shadow-[var(--shadow-panel)] animate-[dialog-in_0.18s_ease-out] outline-none',
          className,
        )}
      >
        <h2 id={titleId} className="text-lg font-semibold tracking-tight">
          {title}
        </h2>
        {description ? (
          <p id={descId} className="mt-1.5 text-sm leading-relaxed text-muted">
            {description}
          </p>
        ) : null}
        <div className="mt-5">{children}</div>
      </div>
    </div>
  )
}

export function ConfirmDialog({
  open,
  onClose,
  onConfirm,
  title,
  description,
  confirmLabel = 'Confirm',
  danger = false,
  busy = false,
}: {
  open: boolean
  onClose: () => void
  onConfirm: () => void
  title: string
  description: string
  confirmLabel?: string
  danger?: boolean
  busy?: boolean
}) {
  return (
    <Dialog open={open} onClose={onClose} title={title} description={description}>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button
          type="button"
          variant={danger ? 'danger' : 'primary'}
          disabled={busy}
          onClick={onConfirm}
        >
          {busy ? 'Working…' : confirmLabel}
        </Button>
      </div>
    </Dialog>
  )
}
