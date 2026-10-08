import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate, useParams } from 'react-router'
import { Button, PageHeader, Skeleton } from '@/components/ui'
import { QueryError } from '@/components/QueryError'
import { MemberFormFields } from '@/features/members/MemberFormFields'
import { MemberPhotoField, photoUploadError, useMemberPhotoUrl } from '@/features/members/MemberPhotoField'
import { memberFormSchema, type MemberFormValues } from '@/features/members/memberFormSchema'
import { ApiError, api } from '@/lib/api'
import type { Member } from '@/lib/types'

export function MemberEditPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const member = useQuery({ queryKey: ['member', id], queryFn: () => api<Member>(`/api/v1/members/${id}`) })
  const form = useForm<MemberFormValues>({ resolver: zodResolver(memberFormSchema) })

  useEffect(() => {
    if (!member.data) return
    form.reset({
      firstName: member.data.firstName,
      lastName: member.data.lastName ?? '',
      email: member.data.email ?? '',
      phone: member.data.phone ?? '',
      dateOfBirth: member.data.dateOfBirth ?? '',
      gender: member.data.gender,
      notes: member.data.notes ?? '',
      deviceAuthority: (member.data.deviceAuthority as 'USER' | 'ADMIN') ?? 'USER',
      serialNumber: member.data.serialNumber ?? '',
    })
  }, [member.data, form])

  const [photo, setPhoto] = useState<File | null>(null)
  const currentPhoto = useMemberPhotoUrl(id)
  const mutation = useMutation({
    mutationFn: async (body: MemberFormValues) => {
      const saved = await api<Member>(`/api/v1/members/${id}`, {
        method: 'PUT',
        body: JSON.stringify({
          ...body,
          email: body.email || null,
          lastName: body.lastName || null,
          phone: body.phone || null,
          dateOfBirth: body.dateOfBirth || null,
          serialNumber: body.serialNumber?.trim() || null,
        }),
      })
      return { member: saved, photoError: photo ? await photoUploadError(saved.id, photo) : null }
    },
    onSuccess: ({ member: saved, photoError }) => {
      void qc.invalidateQueries({ queryKey: ['member-photo', saved.id] })
      void qc.invalidateQueries({ queryKey: ['member-device-sync', saved.id] })
      navigate(`/app/members/${saved.id}`, { state: { photoError } })
    },
  })

  if (member.isLoading) return <Skeleton className="h-40" />
  if (member.error) return <QueryError error={member.error} />

  return (
    <div className="max-w-xl">
      <PageHeader title="Edit member" description={member.data?.memberCode} />
      <p className="mb-4 text-sm text-muted">
        Saving a name publishes a new revision for a reader using desired-state sync. Other readers still get an
        update command.
      </p>
      <form className="space-y-4" onSubmit={form.handleSubmit((v) => mutation.mutate(v))}>
        <MemberFormFields form={form} />
        <MemberPhotoField value={photo} onChange={setPhoto} currentUrl={currentPhoto.url} />
        {mutation.error instanceof ApiError ? <p className="text-sm text-danger">{mutation.error.message}</p> : null}
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Saving…' : 'Save member'}
        </Button>
      </form>
    </div>
  )
}
