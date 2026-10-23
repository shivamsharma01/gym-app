import type { UseFormReturn } from 'react-hook-form'
import { FieldError, Input, Label, Select, Textarea } from '@/components/ui'
import type { MemberFormValues } from '@/features/members/memberFormSchema'
import { useAuth } from '@/lib/useAuth'

export function MemberFormFields({ form }: { form: UseFormReturn<MemberFormValues> }) {
  const canSetAuthority = useAuth().has('USER_MANAGE')
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
        <Label>Gender</Label>
        <Select {...form.register('gender')}>
          <option value="UNSPECIFIED">Unspecified</option>
          <option value="FEMALE">Female</option>
          <option value="MALE">Male</option>
        </Select>
      </div>
      <div>
        <Label>Terminal Authority (Device User Level)</Label>
        <Select {...form.register('deviceAuthority')} disabled={!canSetAuthority}>
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
