import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Button, FieldError, Label } from '@/components/ui'
import { ApiError, api, apiBlob } from '@/lib/api'
import type { FaceView } from '@/lib/types'

const MAX_BYTES = 10 * 1024 * 1024
const MIN_SIDE = 200
const ACCEPTED = ['image/jpeg', 'image/png']

/** Uploads a member photo; the server resizes it and pushes it to every device. */
export function uploadMemberPhoto(memberId: string, file: File) {
  const body = new FormData()
  body.append('file', file)
  return api<FaceView>(`/api/v1/members/${memberId}/face`, { method: 'PUT', body })
}

/** Uploads after the member was saved; returns an error message instead of throwing. */
export async function photoUploadError(memberId: string, file: File): Promise<string | null> {
  try {
    await uploadMemberPhoto(memberId, file)
    return null
  } catch (e) {
    return e instanceof ApiError ? e.message : 'Photo upload failed'
  }
}

/** Object URL for the member's current photo, or null when there is none. */
export function useMemberPhotoUrl(memberId: string | undefined) {
  const photo = useQuery({
    queryKey: ['member-photo', memberId],
    queryFn: () => apiBlob(`/api/v1/members/${memberId}/face`),
    enabled: !!memberId,
  })
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!photo.data) {
      setUrl(null)
      return
    }
    const next = URL.createObjectURL(photo.data)
    setUrl(next)
    return () => URL.revokeObjectURL(next)
  }, [photo.data])
  return { url, isLoading: photo.isLoading }
}

async function validatePhoto(file: File): Promise<string | null> {
  if (!ACCEPTED.includes(file.type)) return 'Use a JPEG or PNG photo.'
  if (file.size > MAX_BYTES) return 'Photo is larger than 10 MB.'
  const url = URL.createObjectURL(file)
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    if (Math.min(img.naturalWidth, img.naturalHeight) < MIN_SIDE) {
      return `Photo is too small (at least ${MIN_SIDE} × ${MIN_SIDE} pixels).`
    }
    return null
  } catch {
    return 'This file could not be read as an image.'
  } finally {
    URL.revokeObjectURL(url)
  }
}

/**
 * Take photo (front camera on phones/tablets) or choose a file. The photo is only uploaded when
 * the member form is saved.
 */
export function MemberPhotoField({
  value,
  onChange,
  currentUrl,
}: {
  value: File | null
  onChange: (file: File | null) => void
  currentUrl?: string | null
}) {
  const cameraInput = useRef<HTMLInputElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<string | null>(null)

  useEffect(() => {
    if (!value) {
      setPreview(null)
      return
    }
    const url = URL.createObjectURL(value)
    setPreview(url)
    return () => URL.revokeObjectURL(url)
  }, [value])

  async function pick(file: File | undefined) {
    if (!file) return
    const problem = await validatePhoto(file)
    setError(problem)
    onChange(problem ? null : file)
  }

  const shown = preview ?? currentUrl ?? null
  return (
    <div>
      <Label>Photo</Label>
      <div className="flex items-center gap-4">
        <div className="flex h-24 w-24 shrink-0 items-center justify-center overflow-hidden rounded-lg border border-line bg-raised text-xs text-muted">
          {shown ? <img src={shown} alt="Member" className="h-full w-full object-cover" /> : 'No photo'}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" onClick={() => cameraInput.current?.click()}>
            Take photo
          </Button>
          <Button type="button" variant="outline" onClick={() => fileInput.current?.click()}>
            Choose photo
          </Button>
          {value ? (
            <Button type="button" variant="ghost" onClick={() => onChange(null)}>
              Undo
            </Button>
          ) : null}
        </div>
      </div>
      <p className="mt-2 text-xs text-muted">
        Face the camera, good light, no cap or sunglasses. Saved with the member and sent to every device.
      </p>
      <input
        ref={cameraInput}
        type="file"
        accept="image/*"
        capture="user"
        className="hidden"
        onChange={(e) => {
          void pick(e.target.files?.[0])
          e.target.value = ''
        }}
      />
      <input
        ref={fileInput}
        type="file"
        accept="image/jpeg,image/png"
        className="hidden"
        onChange={(e) => {
          void pick(e.target.files?.[0])
          e.target.value = ''
        }}
      />
      <FieldError message={error ?? undefined} />
    </div>
  )
}
