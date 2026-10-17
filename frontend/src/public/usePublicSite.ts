import { useQuery } from '@tanstack/react-query'
import { type PublicSite } from '@/lib/brand'
import { useGymSlug } from '@/lib/useGymSlug'
import { publicApi } from '@/lib/publicApi'
import type { Plan } from '@/lib/types'

export function usePublicSite() {
  const slug = useGymSlug()
  return useQuery({
    queryKey: ['public-site', slug],
    queryFn: () => publicApi<PublicSite>(slug, '/api/v1/public/site'),
  })
}

export function usePublicPlans() {
  const slug = useGymSlug()
  return useQuery({
    queryKey: ['public-plans', slug],
    queryFn: () => publicApi<Plan[]>(slug, '/api/v1/public/plans'),
  })
}
