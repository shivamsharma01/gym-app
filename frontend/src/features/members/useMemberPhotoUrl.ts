import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo } from 'react'
import { apiBlob } from '@/lib/api'

/** Object URL for the member's current photo, or null when there is none. */
export function useMemberPhotoUrl(memberId: string | undefined) {
  const photo = useQuery({
    queryKey: ['member-photo', memberId],
    queryFn: () => apiBlob(`/api/v1/members/${memberId}/face`),
    enabled: !!memberId,
  })
  const url = useMemo(
    () => (photo.data ? URL.createObjectURL(photo.data) : null),
    [photo.data],
  )
  useEffect(() => {
    return () => {
      if (url) URL.revokeObjectURL(url)
    }
  }, [url])
  return { url, isLoading: photo.isLoading }
}
