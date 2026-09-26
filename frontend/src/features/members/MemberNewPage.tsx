import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { Controller, useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { Button, FieldError, Input, Label, PageHeader, Select, Textarea } from '@/components/ui'
import { DateOfBirthField } from '@/features/members/DateOfBirthField'
import { memberFormSchema, type MemberFormValues } from '@/features/members/memberFormSchema'
import { ApiError, api } from '@/lib/api'
import type { Member } from '@/lib/types'

export function MemberNewPage() {
  const navigate = useNavigate()
  const form = useForm<MemberFormValues>({
    resolver: zodResolver(memberFormSchema),
    defaultValues: {
      firstName: '',
      lastName: '',
      email: '',
      phone: '',
      dateOfBirth: '',
      gender: 'UNSPECIFIED',
      notes: '',
    },
  })
  const mutation = useMutation({
    mutationFn: (body: MemberFormValues) =>
      api<Member>('/api/v1/members', {
        method: 'POST',
        body: JSON.stringify({
          ...body,
          email: body.email || null,
          lastName: body.lastName || null,
          phone: body.phone || null,
          dateOfBirth: body.dateOfBirth || null,
          memberCode: null,
        }),
      }),
    onSuccess: (m) => navigate(`/app/members/${m.id}`),
  })

  return (
    <div className="max-w-xl">
      <PageHeader title="New member" description="The gym assigns a member code automatically." />
      <form className="space-y-4" onSubmit={form.handleSubmit((v) => mutation.mutate(v))}>
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
          <Label>Notes</Label>
          <Textarea rows={3} {...form.register('notes')} />
        </div>
        {mutation.error instanceof ApiError ? <p className="text-sm text-danger">{mutation.error.message}</p> : null}
        <Button type="submit" disabled={mutation.isPending}>
          Create
        </Button>
      </form>
    </div>
  )
}
