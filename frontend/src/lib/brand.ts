/** Default public brand assets when a gym has not uploaded custom URLs yet. */
export const BRAND_DEFAULTS = {
  logoUrl: '/brand/defaults/logo.svg',
  heroImageUrl: '/brand/defaults/hero.svg',
  trainingImageUrl: '/brand/defaults/training.svg',
  facilitiesImageUrl: '/brand/defaults/facilities.svg',
  tagline: 'Train with intent. Walk in on your face, not a clipboard.',
  about:
    'A serious floor, coaching that respects your time, and door access tied to a real membership — not a sticker on a phone.',
  sectionTrainingTitle: 'Training that respects your time',
  sectionTrainingBody:
    'Strength, conditioning, and coached sessions programmed for people who already know why they showed up.',
  sectionFacilitiesTitle: 'The floor, dialed in',
  sectionFacilitiesBody:
    'Open racks, recovery space, and a door that knows your membership status before you touch a handle.',
} as const

export const DEFAULT_GYM_SLUG =
  (import.meta.env.VITE_DEFAULT_GYM_SLUG as string | undefined)?.trim() || 'downtown-fitness'

export type PublicSite = {
  tenantId: string
  name: string
  slug: string
  displayName: string
  tagline: string | null
  about: string | null
  phone: string | null
  email: string | null
  address: string | null
  hours: string | null
  logoUrl: string | null
  heroImageUrl: string | null
  trainingImageUrl: string | null
  facilitiesImageUrl: string | null
  sectionTrainingTitle: string | null
  sectionTrainingBody: string | null
  sectionFacilitiesTitle: string | null
  sectionFacilitiesBody: string | null
}

export function brandDisplayName(site: PublicSite | undefined | null, fallback = 'Gym') {
  return site?.displayName?.trim() || site?.name?.trim() || fallback
}

export function brandLogo(site: PublicSite | undefined | null) {
  return site?.logoUrl?.trim() || BRAND_DEFAULTS.logoUrl
}

export function brandHero(site: PublicSite | undefined | null) {
  return site?.heroImageUrl?.trim() || BRAND_DEFAULTS.heroImageUrl
}

export function brandTrainingImage(site: PublicSite | undefined | null) {
  return site?.trainingImageUrl?.trim() || BRAND_DEFAULTS.trainingImageUrl
}

export function brandFacilitiesImage(site: PublicSite | undefined | null) {
  return site?.facilitiesImageUrl?.trim() || BRAND_DEFAULTS.facilitiesImageUrl
}

export function brandTagline(site: PublicSite | undefined | null) {
  return site?.tagline?.trim() || BRAND_DEFAULTS.tagline
}

export function brandAbout(site: PublicSite | undefined | null) {
  return site?.about?.trim() || BRAND_DEFAULTS.about
}

export function brandTrainingCopy(site: PublicSite | undefined | null) {
  return {
    title: site?.sectionTrainingTitle?.trim() || BRAND_DEFAULTS.sectionTrainingTitle,
    body: site?.sectionTrainingBody?.trim() || BRAND_DEFAULTS.sectionTrainingBody,
  }
}

export function brandFacilitiesCopy(site: PublicSite | undefined | null) {
  return {
    title: site?.sectionFacilitiesTitle?.trim() || BRAND_DEFAULTS.sectionFacilitiesTitle,
    body: site?.sectionFacilitiesBody?.trim() || BRAND_DEFAULTS.sectionFacilitiesBody,
  }
}

/** Build a public path under /g/{slug}/... */
export function gymPath(slug: string, rest = '') {
  const base = `/g/${slug}`
  if (!rest || rest === '/') return base
  return `${base}${rest.startsWith('/') ? rest : `/${rest}`}`
}
