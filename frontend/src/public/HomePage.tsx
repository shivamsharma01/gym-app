import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '@/lib/api'
import type { Plan } from '@/lib/types'
import { money } from '@/lib/cn'

export type PublicSite = {
  tenantId: string
  name: string
  slug: string
  tagline: string | null
  about: string | null
  phone: string | null
  email: string | null
  address: string | null
  hours: string | null
}

export function usePublicSite() {
  return useQuery({
    queryKey: ['public-site'],
    queryFn: () => api<PublicSite>('/api/v1/public/site'),
  })
}

export function usePublicPlans() {
  return useQuery({
    queryKey: ['public-plans'],
    queryFn: () => api<Plan[]>('/api/v1/public/plans'),
  })
}

export function HomePage() {
  const site = usePublicSite()
  const plans = usePublicPlans()
  return (
    <main>
      <section className="relative overflow-hidden px-4 py-24 md:py-36">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,#c8f54222,transparent_40%),radial-gradient(circle_at_80%_0%,#ffffff10,transparent_35%)]" />
        <div className="relative mx-auto max-w-6xl">
          <p className="text-xs font-semibold uppercase tracking-[0.3em] text-[#c8f542]">True Gym</p>
          <h1 className="mt-4 max-w-3xl text-5xl font-extrabold leading-[1.05] md:text-7xl">
            {site.data?.tagline || 'Train with intent. Walk in on your face, not a clipboard.'}
          </h1>
          <p className="mt-6 max-w-xl text-lg text-white/70">
            {site.data?.about ||
              'A serious floor, coaching that respects your time, and door access tied to a real membership — not a sticker on a phone.'}
          </p>
          <div className="mt-10 flex flex-wrap gap-3">
            <Link to="/contact" className="rounded-full bg-[#c8f542] px-6 py-3 font-bold text-[#14180f]">
              Book a visit
            </Link>
            <Link to="/membership-plans" className="rounded-full border border-white/20 px-6 py-3 font-bold">
              See plans
            </Link>
          </div>
        </div>
      </section>
      <section className="mx-auto grid max-w-6xl gap-6 px-4 pb-20 md:grid-cols-3">
        {['Strength floor', 'Coached sessions', 'Face access'].map((title) => (
          <div key={title} className="rounded-3xl border border-white/10 bg-white/5 p-6">
            <h2 className="text-xl font-extrabold">{title}</h2>
            <p className="mt-2 text-sm text-white/60">
              Built for people who already know why they are here. Equipment, programming, and the door all match that standard.
            </p>
          </div>
        ))}
      </section>
      <section className="mx-auto max-w-6xl px-4 pb-24">
        <h2 className="text-3xl font-extrabold">Plans</h2>
        <div className="mt-6 grid gap-4 md:grid-cols-3">
          {(plans.data ?? []).slice(0, 3).map((plan) => (
            <div key={plan.id} className="rounded-3xl border border-white/10 p-6">
              <div className="text-sm text-white/50">{plan.durationDays} days</div>
              <div className="mt-1 text-2xl font-extrabold">{plan.name}</div>
              <div className="mt-4 text-3xl font-extrabold text-[#c8f542]">{money(plan.price, plan.currency)}</div>
            </div>
          ))}
        </div>
        {plans.data?.length === 0 ? (
          <p className="mt-4 text-white/50">Plans appear here once the gym publishes them.</p>
        ) : null}
      </section>
    </main>
  )
}
