import { format, isValid, parse } from 'date-fns'
import { CalendarIcon, X } from 'lucide-react'
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { DayPicker, type Matcher } from 'react-day-picker'
import { Button } from '@/components/ui'
import { cn } from '@/lib/cn'
import 'react-day-picker/style.css'

const ISO = 'yyyy-MM-dd'
const POPOVER_HEIGHT = 360

function parseIso(value?: string) {
  if (!value) return undefined
  const d = parse(value, ISO, new Date())
  return isValid(d) ? d : undefined
}

export type DateFieldProps = {
  id?: string
  /** ISO date `yyyy-MM-dd`, or empty. */
  value?: string
  onChange: (value: string) => void
  /** Earliest selectable ISO date (inclusive). */
  min?: string
  /** Latest selectable ISO date (inclusive). */
  max?: string
  disabled?: boolean
  clearable?: boolean
  placeholder?: string
  ariaLabel?: string
  /** Month shown when no value is set. */
  defaultMonth?: Date
  startMonth?: Date
  endMonth?: Date
  className?: string
}

/**
 * Calendar date picker. The popover is portalled with fixed positioning so it is not clipped by
 * dialogs or cards that use `overflow-hidden`.
 */
export function DateField({
  id,
  value = '',
  onChange,
  min,
  max,
  disabled,
  clearable = false,
  placeholder = 'Pick a date',
  ariaLabel = 'Choose date',
  defaultMonth,
  startMonth = new Date(2015, 0),
  endMonth = new Date(new Date().getFullYear() + 5, 11),
  className,
}: DateFieldProps) {
  const autoId = useId()
  const fieldId = id ?? autoId
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const popoverRef = useRef<HTMLDivElement>(null)

  const selected = parseIso(value)
  const minDate = parseIso(min)
  const maxDate = parseIso(max)
  const disabledDays: Matcher[] = []
  if (minDate) disabledDays.push({ before: minDate })
  if (maxDate) disabledDays.push({ after: maxDate })

  useLayoutEffect(() => {
    if (!open) return
    function place() {
      const rect = triggerRef.current?.getBoundingClientRect()
      if (!rect) return
      const below = window.innerHeight - rect.bottom
      const top = below < POPOVER_HEIGHT && rect.top > below ? rect.top - POPOVER_HEIGHT - 8 : rect.bottom + 8
      setPos({ top: Math.max(8, top), left: Math.max(8, Math.min(rect.left, window.innerWidth - 320)) })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      const target = e.target as Node
      if (triggerRef.current?.contains(target) || popoverRef.current?.contains(target)) return
      setOpen(false)
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        setOpen(false)
        triggerRef.current?.focus()
      }
    }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className={cn('flex gap-2', className)}>
      <button
        ref={triggerRef}
        type="button"
        id={fieldId}
        disabled={disabled}
        className={cn(
          'flex w-full items-center gap-2 rounded-lg border border-line-strong bg-raised px-3 py-2 text-left text-sm text-ink',
          'transition hover:border-accent/40 focus:border-accent/50 focus:outline-none disabled:opacity-45',
        )}
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="dialog"
        aria-expanded={open}
      >
        <CalendarIcon className="h-4 w-4 shrink-0 text-accent" aria-hidden />
        <span className={selected ? 'text-ink' : 'text-muted'}>
          {selected ? format(selected, 'dd MMM yyyy') : placeholder}
        </span>
      </button>
      {clearable && value ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          className="shrink-0 px-2"
          disabled={disabled}
          onClick={() => onChange('')}
          aria-label={`Clear ${ariaLabel.toLowerCase()}`}
        >
          <X className="h-4 w-4" />
        </Button>
      ) : null}
      {open && pos
        ? createPortal(
            <div
              ref={popoverRef}
              className="rdp-theme fixed z-[60] rounded-xl border border-line bg-panel p-3 text-ink shadow-[var(--shadow-panel)]"
              style={{ top: pos.top, left: pos.left }}
              role="dialog"
              aria-label={ariaLabel}
            >
              <DayPicker
                mode="single"
                captionLayout="dropdown"
                selected={selected}
                disabled={disabledDays}
                defaultMonth={selected ?? defaultMonth ?? minDate ?? new Date()}
                startMonth={startMonth}
                endMonth={endMonth}
                onSelect={(day) => {
                  if (!day) return
                  onChange(format(day, ISO))
                  setOpen(false)
                }}
              />
            </div>,
            document.body,
          )
        : null}
    </div>
  )
}
