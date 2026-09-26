import { format, parse, isValid } from 'date-fns'
import { CalendarIcon, X } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { DayPicker } from 'react-day-picker'
import { Button, Input } from '@/components/ui'
import { cn } from '@/lib/cn'
import 'react-day-picker/style.css'

type Props = {
  id?: string
  value?: string
  onChange: (value: string) => void
  disabled?: boolean
}

/** Read-only DOB field filled only via the calendar (no keyboard entry). */
export function DateOfBirthField({ id, value = '', onChange, disabled }: Props) {
  const autoId = useId()
  const fieldId = id ?? autoId
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  const selected = (() => {
    if (!value) return undefined
    const d = parse(value, 'yyyy-MM-dd', new Date())
    return isValid(d) ? d : undefined
  })()

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  return (
    <div ref={rootRef} className="relative">
      <div className="flex gap-2">
        <button
          type="button"
          id={fieldId}
          disabled={disabled}
          className={cn(
            'flex w-full items-center gap-2 rounded-lg border border-line bg-canvas px-3 py-2 text-left text-sm text-ink',
            'shadow-[inset_0_1px_0_rgb(255_255_255/0.02)] transition hover:border-line-strong',
            'focus:border-accent/50 focus:outline-none disabled:opacity-45',
          )}
          onClick={() => setOpen((v) => !v)}
          aria-haspopup="dialog"
          aria-expanded={open}
        >
          <CalendarIcon className="h-4 w-4 shrink-0 text-muted" />
          <span className={selected ? 'text-ink' : 'text-muted'}>
            {selected ? format(selected, 'dd MMM yyyy') : 'Pick a date'}
          </span>
        </button>
        {value ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="shrink-0 px-2"
            disabled={disabled}
            onClick={() => onChange('')}
            aria-label="Clear date of birth"
          >
            <X className="h-4 w-4" />
          </Button>
        ) : null}
      </div>
      {/* Keep RHF value in sync without allowing typing */}
      <Input type="hidden" readOnly value={value} tabIndex={-1} aria-hidden />
      {open ? (
        <div
          className="absolute z-30 mt-2 rounded-xl border border-line bg-panel p-3 shadow-[var(--shadow-panel)]"
          role="dialog"
          aria-label="Date of birth calendar"
        >
          <DayPicker
            mode="single"
            captionLayout="dropdown"
            selected={selected}
            disabled={{ after: new Date() }}
            defaultMonth={selected ?? new Date(2000, 0, 1)}
            startMonth={new Date(1920, 0)}
            endMonth={new Date()}
            onSelect={(day) => {
              if (!day) {
                onChange('')
                return
              }
              onChange(format(day, 'yyyy-MM-dd'))
              setOpen(false)
            }}
          />
        </div>
      ) : null}
    </div>
  )
}
