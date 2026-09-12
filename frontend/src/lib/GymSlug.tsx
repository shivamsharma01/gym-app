import { createContext, useContext, useMemo, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { DEFAULT_GYM_SLUG } from '@/lib/brand'

const GymSlugContext = createContext(DEFAULT_GYM_SLUG)

export function GymSlugProvider({ children, slug }: { children: ReactNode; slug?: string }) {
  const value = slug?.trim() || DEFAULT_GYM_SLUG
  return <GymSlugContext.Provider value={value}>{children}</GymSlugContext.Provider>
}

export function useGymSlug() {
  return useContext(GymSlugContext)
}

/** Reads :gymSlug from the route and provides it to public API calls / links. */
export function GymSlugFromRoute({ children }: { children: ReactNode }) {
  const { gymSlug } = useParams()
  const slug = useMemo(() => gymSlug?.trim() || DEFAULT_GYM_SLUG, [gymSlug])
  return <GymSlugProvider slug={slug}>{children}</GymSlugProvider>
}
