import { cva, type VariantProps } from 'class-variance-authority'
import type {
  ButtonHTMLAttributes,
  HTMLAttributes,
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TableHTMLAttributes,
  TdHTMLAttributes,
  TextareaHTMLAttributes,
  ThHTMLAttributes,
} from 'react'
import { Link } from 'react-router'
import { cn } from '@/lib/cn'

const button = cva(
  'inline-flex items-center justify-center gap-2 rounded-lg text-sm font-semibold transition duration-150 active:scale-[0.98] disabled:pointer-events-none disabled:opacity-45',
  {
    variants: {
      variant: {
        primary: 'bg-accent text-accent-ink shadow-[0_0_0_1px_rgb(0_0_0/0.15)] hover:brightness-110',
        ghost: 'text-muted hover:bg-raised hover:text-ink',
        danger: 'bg-danger/90 text-white hover:brightness-110',
        outline: 'border border-line bg-transparent text-ink hover:border-line-strong hover:bg-raised',
        soft: 'bg-accent-soft text-accent hover:brightness-110',
      },
      size: {
        sm: 'px-2.5 py-1.5 text-xs',
        md: 'px-3.5 py-2',
        lg: 'px-4 py-2.5',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof button>

export function Button({ className, variant, size, ...props }: ButtonProps) {
  return <button className={cn(button({ variant, size }), className)} {...props} />
}

const fieldControl =
  'w-full rounded-lg border border-line bg-canvas px-3 py-2 text-sm text-ink shadow-[inset_0_1px_0_rgb(255_255_255/0.02)] transition placeholder:text-muted/80 hover:border-line-strong focus:border-accent/50'

export function Input(props: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cn(fieldControl, props.className)} />
}

export function Select(props: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={cn(fieldControl, props.className)} />
}

export function Textarea(props: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea {...props} className={cn(fieldControl, 'min-h-24 resize-y', props.className)} />
}

export function Label({
  children,
  htmlFor,
  className,
}: {
  children: ReactNode
  htmlFor?: string
  className?: string
}) {
  return (
    <label
      htmlFor={htmlFor}
      className={cn(
        'mb-1.5 block text-[11px] font-semibold uppercase tracking-[0.08em] text-muted',
        className,
      )}
    >
      {children}
    </label>
  )
}

export function Field({
  label,
  htmlFor,
  error,
  hint,
  children,
  className,
}: {
  label: string
  htmlFor?: string
  error?: string
  hint?: string
  children: ReactNode
  className?: string
}) {
  return (
    <div className={className}>
      <Label htmlFor={htmlFor}>{label}</Label>
      {children}
      {hint && !error ? <p className="mt-1.5 text-xs text-muted">{hint}</p> : null}
      <FieldError message={error} />
    </div>
  )
}

export function Card({
  children,
  className,
  padded = true,
}: {
  children: ReactNode
  className?: string
  padded?: boolean
}) {
  return (
    <div
      className={cn(
        'rounded-2xl border border-line bg-panel shadow-[var(--shadow-panel)]',
        padded && 'p-5',
        className,
      )}
    >
      {children}
    </div>
  )
}

export function Badge({
  children,
  tone = 'muted',
}: {
  children: ReactNode
  tone?: 'ok' | 'warn' | 'danger' | 'muted' | 'accent'
}) {
  const colors = {
    ok: 'bg-ok/12 text-ok ring-ok/20',
    warn: 'bg-warn/12 text-warn ring-warn/20',
    danger: 'bg-danger/12 text-danger ring-danger/20',
    muted: 'bg-raised text-muted ring-line',
    accent: 'bg-accent-soft text-accent ring-accent/25',
  }
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold tracking-wide ring-1 ring-inset',
        colors[tone],
      )}
    >
      {children}
    </span>
  )
}

export function FieldError({ message }: { message?: string }) {
  if (!message) return null
  return (
    <p className="mt-1.5 text-xs text-danger" role="alert">
      {message}
    </p>
  )
}

export function EmptyState({
  title,
  body,
  action,
  icon,
}: {
  title: string
  body: string
  action?: ReactNode
  icon?: ReactNode
}) {
  return (
    <div className="rounded-2xl border border-dashed border-line bg-panel/40 px-6 py-14 text-center">
      {icon ? <div className="mx-auto mb-4 flex h-11 w-11 items-center justify-center rounded-xl bg-raised text-muted">{icon}</div> : null}
      <h3 className="text-base font-semibold tracking-tight">{title}</h3>
      <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-muted">{body}</p>
      {action ? <div className="mt-5 flex justify-center">{action}</div> : null}
    </div>
  )
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-xl bg-raised', className)} aria-hidden />
}

export function PageHeader({
  title,
  description,
  actions,
  eyebrow,
}: {
  title: string
  description?: string
  actions?: ReactNode
  eyebrow?: string
}) {
  return (
    <div className="mb-7 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {eyebrow ? (
          <p className="mb-1 text-[11px] font-semibold uppercase tracking-[0.14em] text-muted">{eyebrow}</p>
        ) : null}
        <h1 className="text-[1.75rem] font-bold leading-tight tracking-tight text-ink sm:text-[1.875rem]">{title}</h1>
        {description ? <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}

export function SectionTitle({
  title,
  description,
  actions,
}: {
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h2 className="text-sm font-semibold tracking-tight text-ink">{title}</h2>
        {description ? <p className="mt-0.5 text-xs text-muted">{description}</p> : null}
      </div>
      {actions}
    </div>
  )
}

export function StatCard({
  label,
  value,
  hint,
  to,
  loading,
}: {
  label: string
  value?: ReactNode
  hint?: ReactNode
  to?: string
  loading?: boolean
}) {
  const inner = (
    <>
      <div className="text-[11px] font-semibold uppercase tracking-[0.1em] text-muted">{label}</div>
      <div className="mt-2.5 text-[1.75rem] font-bold tracking-tight tabular-nums">
        {loading ? <Skeleton className="h-8 w-16" /> : (value ?? '—')}
      </div>
      {hint ? <div className="mt-1.5 text-xs text-muted">{hint}</div> : null}
    </>
  )

  if (to) {
    return (
      <Link
        to={to}
        className="block rounded-2xl border border-line bg-panel p-5 shadow-[var(--shadow-panel)] transition duration-150 hover:border-accent/35 hover:bg-raised/40"
      >
        {inner}
      </Link>
    )
  }

  return <Card className="p-5">{inner}</Card>
}

export function Toolbar({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cn('mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between', className)}>{children}</div>
}

export function Panel({
  title,
  description,
  actions,
  children,
  className,
}: {
  title?: string
  description?: string
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <Card className={cn('overflow-hidden p-0', className)} padded={false}>
      {title || actions ? (
        <div className="flex flex-col gap-2 border-b border-line px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            {title ? <h3 className="text-sm font-semibold tracking-tight">{title}</h3> : null}
            {description ? <p className="mt-0.5 text-xs text-muted">{description}</p> : null}
          </div>
          {actions}
        </div>
      ) : null}
      <div className="p-5">{children}</div>
    </Card>
  )
}

export function TableShell({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('overflow-x-auto rounded-2xl border border-line bg-panel shadow-[var(--shadow-panel)]', className)}>
      {children}
    </div>
  )
}

export function Table({ className, ...props }: TableHTMLAttributes<HTMLTableElement>) {
  return <table className={cn('w-full min-w-[640px] text-left text-sm', className)} {...props} />
}

export function THead({ children }: { children: ReactNode }) {
  return <thead className="border-b border-line bg-raised/70 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">{children}</thead>
}

export function Th({ className, ...props }: ThHTMLAttributes<HTMLTableCellElement>) {
  return <th className={cn('px-4 py-3 font-semibold', className)} {...props} />
}

export function Td({ className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('px-4 py-3.5 align-middle', className)} {...props} />
}

export function Tr({ className, ...props }: HTMLAttributes<HTMLTableRowElement>) {
  return <tr className={cn('border-t border-line/80 transition-colors hover:bg-raised/50', className)} {...props} />
}

export function FormSection({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: ReactNode
}) {
  return (
    <fieldset className="space-y-4 border-0 p-0">
      <legend className="mb-3 w-full">
        <span className="block text-sm font-semibold tracking-tight">{title}</span>
        {description ? <span className="mt-0.5 block text-xs text-muted">{description}</span> : null}
      </legend>
      {children}
    </fieldset>
  )
}

export function LiveDot({ state }: { state: 'live' | 'reconnecting' | 'offline' | 'off' | 'down' | string }) {
  const normalized = state === 'down' ? 'reconnecting' : state
  const tone =
    normalized === 'live'
      ? 'bg-ok shadow-[0_0_8px_var(--color-ok)]'
      : normalized === 'reconnecting'
        ? 'bg-warn'
        : 'bg-muted'
  const label =
    normalized === 'live' ? 'Live' : normalized === 'reconnecting' ? 'Reconnecting' : 'Offline'
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted">
      <span className={cn('h-1.5 w-1.5 rounded-full', tone)} aria-hidden />
      <span className="sr-only">Connection:</span>
      {label}
    </span>
  )
}
