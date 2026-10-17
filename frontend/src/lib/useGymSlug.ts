import { useContext } from 'react'
import { GymSlugContext } from '@/lib/gym-slug-context'

export function useGymSlug() {
  return useContext(GymSlugContext)
}
