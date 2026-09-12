import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { z } from 'zod'
import { Button, FieldError, Input, Label, PageHeader, Select, Textarea } from '@/components/ui'
import { ApiError, api } from '@/lib/api'
import type { Member } from '@/lib/types'

const schema = z.object({
  firstName: z.string().min(1),
  lastName: z.string().optional(),
  email: z.string().email().optional().or(z.literal('')),
  phone: z.string().optional(),
  dateOfBirth: z.string().optional(),
  gender: z.string(),
  notes: z.string().optional(),
})

type Form = z.infer<typeof schema>

export function MemberNewPage() {
  const navigate = useNavigate()
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: { firstName: '', lastName: '', email: '', phone: '', gender: 'UNSPECIFIED', notes: '' },
  })
  const mutation = useMutation({
    mutationFn: (body: Form) =>
      api<Member>('/api/v1/members', {
        method: 'POST',
        body: JSON.stringify({
          ...body,
          email: body.email || null,
          lastName: body.lastName || null,
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
        </div>
        <div>
          <Label>Phone</Label>
          <Input {...form.register('phone')} />
        </div>
        <div>
          <Label>Date of birth</Label>
          <Input type="date" {...form.register('dateOfBirth')} />
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
        <Button type="submit" disabled={mutation.isPending}>Create</Button>
      </form>
    </div>
  )
}
