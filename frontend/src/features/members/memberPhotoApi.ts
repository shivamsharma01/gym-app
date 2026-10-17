import { ApiError, api } from '@/lib/api'
import type { FaceView } from '@/lib/types'

/** Uploads a member photo. The server stores it and publishes it to the gym's readers. */
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
