import { format } from 'date-fns'
import { DateField } from '@/components/DateField'

type Props = {
  id?: string
  value?: string
  onChange: (value: string) => void
  disabled?: boolean
}

/** Read-only DOB field filled only via the calendar (no keyboard entry). */
export function DateOfBirthField({ id, value = '', onChange, disabled }: Props) {
  return (
    <DateField
      id={id}
      value={value}
      onChange={onChange}
      disabled={disabled}
      clearable
      max={format(new Date(), 'yyyy-MM-dd')}
      ariaLabel="Date of birth"
      defaultMonth={new Date(2000, 0, 1)}
      startMonth={new Date(1920, 0)}
      endMonth={new Date()}
    />
  )
}
