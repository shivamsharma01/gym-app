import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { Button, PageHeader } from '@/components/ui'
import { MemberFormFields } from '@/features/members/MemberFormFields'
import { MemberPhotoField, photoUploadError } from '@/features/members/MemberPhotoField'
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
      deviceAuthority: 'USER',
      serialNumber: '',
    },
  })
  const nextSerial = useQuery({
    queryKey: ['members', 'next-serial'],
    queryFn: () => api<{ serialNumber: string }>('/api/v1/members/next-serial'),
  })
  useEffect(() => {
    if (nextSerial.data && !form.getValues('serialNumber')) {
      form.setValue('serialNumber', nextSerial.data.serialNumber)
    }
  }, [nextSerial.data, form])
  const [photo, setPhoto] = useState<File | null>(null)
  const mutation = useMutation({
    mutationFn: async (body: MemberFormValues) => {
      const member = await api<Member>('/api/v1/members', {
        method: 'POST',
        body: JSON.stringify({
          ...body,
          email: body.email || null,
          lastName: body.lastName || null,
          phone: body.phone || null,
          dateOfBirth: body.dateOfBirth || null,
          serialNumber: body.serialNumber?.trim() || null,
          memberCode: null,
        }),
      })
      return { member, photoError: photo ? await photoUploadError(member.id, photo) : null }
    },
    onSuccess: ({ member, photoError }) => navigate(`/app/members/${member.id}`, { state: { photoError } }),
  })

  return (
    <div className="max-w-xl">
      <PageHeader title="New member" description="The serial number starts at the next free number; change it if needed." />
      <form className="space-y-4" onSubmit={form.handleSubmit((v) => mutation.mutate(v))}>
        <MemberFormFields form={form} />
        <MemberPhotoField value={photo} onChange={setPhoto} />
        {mutation.error instanceof ApiError ? <p className="text-sm text-danger">{mutation.error.message}</p> : null}
        <div className="flex gap-2">
          <Button type="submit" disabled={mutation.isPending}>
            {mutation.isPending ? 'Saving…' : 'Save member'}
          </Button>
          <Link to="/app/members">
            <Button type="button" variant="outline">
              Cancel
            </Button>
          </Link>
        </div>
      </form>
    </div>
  )
}
