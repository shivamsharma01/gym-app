import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { BackLink } from '@/components/BackLink'
import { Button, PageHeader } from '@/components/ui'
import { MemberFormFields } from '@/features/members/MemberFormFields'
import { MemberPhotoField } from '@/features/members/MemberPhotoField'
import { memberFormSchema, type MemberFormValues } from '@/features/members/memberFormSchema'
import { ApiError, api } from '@/lib/api'
import { toastOk } from '@/lib/toast'
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
      gender: 'UNSPECIFIED',
      notes: '',
      deviceAuthority: 'USER',
    },
  })
  const [photo, setPhoto] = useState<File | null>(null)
  const mutation = useMutation({
    mutationFn: async (body: MemberFormValues) => {
      if (!photo) {
        throw new ApiError(400, 'A face photo is required')
      }
      const payload = {
        ...body,
        email: body.email || null,
        lastName: body.lastName || null,
        phone: body.phone || null,
        dateOfBirth: null,
        memberCode: null,
      }
      const formData = new FormData()
      formData.append('member', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
      formData.append('face', photo)
      return api<Member>('/api/v1/members', { method: 'POST', body: formData })
    },
    onSuccess: (member) => {
      toastOk('Member added.')
      navigate(`/app/members/${member.id}`)
    },
  })

  return (
    <div className="max-w-xl">
      <BackLink to="/app/members" label="Back to members" />
      <PageHeader
        title="New member"
        description="A photo is required. The member is sent to every reader."
      />
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
