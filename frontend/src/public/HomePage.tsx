import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import {
  brandAbout,
  brandDisplayName,
  brandFacilitiesCopy,
  brandFacilitiesImage,
  brandHero,
  brandTagline,
  brandTrainingCopy,
  brandTrainingImage,
  gymPath,
  type PublicSite,
} from '@/lib/brand'
import { money } from '@/lib/cn'
import { useGymSlug } from '@/lib/GymSlug'
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

export function HomePage() {
  const slug = useGymSlug()
  const site = usePublicSite()
  const plans = usePublicPlans()
  const name = brandDisplayName(site.data)
  const training = brandTrainingCopy(site.data)
  const facilities = brandFacilitiesCopy(site.data)

  return (
    <main>
      {/* Hero — one composition: brand, headline, support, CTAs, full-bleed image */}
      <section className="relative min-h-[88vh] overflow-hidden">
        <img
          src={brandHero(site.data)}
          alt=""
          className="absolute inset-0 h-full w-full object-cover motion-safe:animate-hero-zoom"
        />
        <div className="absolute inset-0 bg-gradient-to-r from-black/85 via-black/55 to-black/25" />
        <div className="relative mx-auto flex min-h-[88vh] max-w-6xl flex-col justify-end px-4 pb-16 pt-28 md:pb-24">
          <p className="motion-safe:animate-fade-up text-4xl font-extrabold tracking-tight text-[#c8f542] sm:text-5xl md:text-7xl">
            {name}
          </p>
          <h1 className="motion-safe:animate-fade-up mt-4 max-w-3xl text-2xl font-semibold leading-snug text-white/95 delay-100 sm:text-3xl md:text-4xl">
            {brandTagline(site.data)}
          </h1>
          <p className="motion-safe:animate-fade-up mt-5 max-w-xl text-base text-white/70 delay-200 md:text-lg">
            {brandAbout(site.data)}
          </p>
          <div className="motion-safe:animate-fade-up mt-10 flex flex-wrap gap-3 delay-300">
            <Link
              to={gymPath(slug, '/contact')}
              className="rounded-full bg-[#c8f542] px-6 py-3 font-bold text-[#14180f] transition hover:brightness-110 hover:scale-[1.02]"
            >
              Book a visit
            </Link>
            <Link
              to={gymPath(slug, '/membership-plans')}
              className="rounded-full border border-white/25 px-6 py-3 font-bold transition hover:border-white/60 hover:bg-white/5"
            >
              See plans
            </Link>
          </div>
        </div>
      </section>

      {/* Training */}
      <section className="border-t border-white/10">
        <div className="mx-auto grid max-w-6xl items-center gap-10 px-4 py-20 md:grid-cols-2 md:py-28">
          <div className="motion-safe:animate-fade-up order-2 md:order-1">
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-[#c8f542]">Training</p>
            <h2 className="mt-3 text-3xl font-extrabold md:text-4xl">{training.title}</h2>
            <p className="mt-4 text-white/65">{training.body}</p>
            <Link to={gymPath(slug, '/services')} className="mt-8 inline-block font-semibold text-[#c8f542] hover:underline">
              Explore services
            </Link>
          </div>
          <div className="order-1 overflow-hidden md:order-2">
            <img
              src={brandTrainingImage(site.data)}
              alt=""
              className="aspect-[4/3] w-full object-cover motion-safe:transition-transform motion-safe:duration-700 hover:scale-[1.03]"
            />
          </div>
        </div>
      </section>

      {/* Facilities */}
      <section className="border-t border-white/10 bg-white/[0.02]">
        <div className="mx-auto grid max-w-6xl items-center gap-10 px-4 py-20 md:grid-cols-2 md:py-28">
          <div className="overflow-hidden">
            <img
              src={brandFacilitiesImage(site.data)}
              alt=""
              className="aspect-[4/3] w-full object-cover motion-safe:transition-transform motion-safe:duration-700 hover:scale-[1.03]"
            />
          </div>
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.25em] text-[#c8f542]">Facilities</p>
            <h2 className="mt-3 text-3xl font-extrabold md:text-4xl">{facilities.title}</h2>
            <p className="mt-4 text-white/65">{facilities.body}</p>
            <Link to={gymPath(slug, '/facilities')} className="mt-8 inline-block font-semibold text-[#c8f542] hover:underline">
              Tour the floor
            </Link>
          </div>
        </div>
      </section>

      {/* Plans CTA */}
      <section className="border-t border-white/10">
        <div className="mx-auto max-w-6xl px-4 py-20 md:py-28">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <div>
              <p className="text-xs font-semibold uppercase tracking-[0.25em] text-[#c8f542]">Membership</p>
              <h2 className="mt-3 text-3xl font-extrabold md:text-4xl">Plans that match how you train</h2>
            </div>
            <Link to={gymPath(slug, '/membership-plans')} className="font-semibold text-[#c8f542] hover:underline">
              View all plans
            </Link>
          </div>
          <div className="mt-10 grid gap-4 md:grid-cols-3">
            {(plans.data ?? []).slice(0, 3).map((plan, i) => (
              <div
                key={plan.id}
                className="border border-white/10 bg-white/[0.03] p-6 motion-safe:transition-colors hover:border-[#c8f542]/40"
                style={{ animationDelay: `${i * 80}ms` }}
              >
                <div className="text-sm text-white/50">{plan.durationDays} days</div>
                <div className="mt-1 text-2xl font-extrabold">{plan.name}</div>
                <div className="mt-4 text-3xl font-extrabold text-[#c8f542]">{money(plan.price, plan.currency)}</div>
              </div>
            ))}
          </div>
          {plans.data?.length === 0 ? (
            <p className="mt-6 text-white/50">Plans appear here once the gym publishes them.</p>
          ) : null}
        </div>
      </section>

      {/* Contact strip */}
      <section className="border-t border-white/10 bg-[#c8f542] text-[#14180f]">
        <div className="mx-auto flex max-w-6xl flex-col items-start justify-between gap-6 px-4 py-14 md:flex-row md:items-center">
          <div>
            <h2 className="text-3xl font-extrabold">Ready to walk in?</h2>
            <p className="mt-2 max-w-lg text-[#14180f]/80">
              Tell {name} how you train. Staff will follow up — this is not a generic lead form farm.
            </p>
          </div>
          <Link
            to={gymPath(slug, '/contact')}
            className="rounded-full bg-[#14180f] px-6 py-3 font-bold text-[#c8f542] transition hover:brightness-125"
          >
            Contact {name}
          </Link>
        </div>
      </section>
    </main>
  )
}
