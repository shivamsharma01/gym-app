import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { Button, Label, PageHeader, Select } from '@/components/ui'
import { MemberFormFields } from '@/features/members/MemberFormFields'
import { MemberPhotoField, photoUploadError } from '@/features/members/MemberPhotoField'
import { memberFormSchema, type MemberFormValues } from '@/features/members/memberFormSchema'
import { ApiError, api } from '@/lib/api'
import type { Device, Member, PageResponse } from '@/lib/types'

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
  const [readerId, setReaderId] = useState('')
  const devices = useQuery({
    queryKey: ['devices', 'member-create'],
    queryFn: () => api<PageResponse<Device>>('/api/v1/devices?page=0&size=50'),
  })
  const readers = (devices.data?.content ?? []).filter((device) => device.projectionEnabled)
  useEffect(() => {
    if (readers.length === 1 && !readerId) {
      setReaderId(readers[0].id)
    }
  }, [readers, readerId])
  const mutation = useMutation({
    mutationFn: async (body: MemberFormValues) => {
      const payload = {
        ...body,
        email: body.email || null,
        lastName: body.lastName || null,
        phone: body.phone || null,
        dateOfBirth: body.dateOfBirth || null,
        serialNumber: body.serialNumber?.trim() || null,
        memberCode: null,
      }
      if (readers.length > 0) {
        if (!readerId) {
          throw new ApiError(400, 'Choose the reader')
        }
        if (!photo) {
          throw new ApiError(400, 'A face photo is required for this reader')
        }
        const formData = new FormData()
        formData.append('member', new Blob([JSON.stringify(payload)], { type: 'application/json' }))
        formData.append('face', photo)
        formData.append('readerId', readerId)
        const member = await api<Member>('/api/v1/members', { method: 'POST', body: formData })
        return { member, photoError: null as string | null }
      }
      const member = await api<Member>('/api/v1/members', {
        method: 'POST',
        body: JSON.stringify(payload),
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
        {readers.length > 0 ? (
          <div>
            <Label>Reader</Label>
            <Select value={readerId} onChange={(event) => setReaderId(event.target.value)}>
              <option value="">Select a reader</option>
              {readers.map((device) => (
                <option key={device.id} value={device.id}>
                  {device.name}
                </option>
              ))}
            </Select>
          </div>
        ) : null}
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
