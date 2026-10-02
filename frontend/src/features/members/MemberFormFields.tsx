import { Controller, type UseFormReturn } from 'react-hook-form'
import { FieldError, Input, Label, Select, Textarea } from '@/components/ui'
import { DateOfBirthField } from '@/features/members/DateOfBirthField'
import type { MemberFormValues } from '@/features/members/memberFormSchema'

export function MemberFormFields({ form }: { form: UseFormReturn<MemberFormValues> }) {
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <Label>First name</Label>
          <Input {...form.register('firstName')} />
          <FieldError message={form.formState.errors.firstName?.message} />
        </div>
        <div>
          <Label>Last name</Label>
          <Input {...form.register('lastName')} />
        </div>
      </div>
      <div>
        <Label>Serial number</Label>
        <Input inputMode="numeric" className="font-mono" {...form.register('serialNumber')} />
        <p className="mt-1 text-xs text-muted">
          The number on the face readers. Changing it moves this member on the readers; their record stays the same.
        </p>
        <FieldError message={form.formState.errors.serialNumber?.message} />
      </div>
      <div>
        <Label>Email</Label>
        <Input type="email" {...form.register('email')} />
        <FieldError message={form.formState.errors.email?.message} />
      </div>
      <div>
        <Label>Phone</Label>
        <Input inputMode="numeric" maxLength={10} placeholder="10 digits" {...form.register('phone')} />
        <FieldError message={form.formState.errors.phone?.message} />
      </div>
      <div>
        <Label>Date of birth</Label>
        <Controller
          control={form.control}
          name="dateOfBirth"
          render={({ field }) => (
            <DateOfBirthField value={field.value} onChange={field.onChange} />
          )}
        />
        <FieldError message={form.formState.errors.dateOfBirth?.message} />
      </div>
      <div>
        <Label>Gender</Label>
        <Select {...form.register('gender')}>
          <option value="UNSPECIFIED">Unspecified</option>
          <option value="FEMALE">Female</option>
          <option value="MALE">Male</option>
          <option value="OTHER">Other</option>
        </Select>
      </div>
      <div>
        <Label>Terminal Authority (Device User Level)</Label>
        <Select {...form.register('deviceAuthority')}>
          <option value="USER">Standard User (Member)</option>
          <option value="ADMIN">Terminal Admin (Staff / Owner)</option>
        </Select>
      </div>
      <div>
        <Label>Notes</Label>
        <Textarea rows={3} {...form.register('notes')} />
      </div>
    </>
  )
}
