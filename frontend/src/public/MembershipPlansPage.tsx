import { Link } from 'react-router'
import { gymPath } from '@/lib/brand'
import { money } from '@/lib/cn'
import { useGymSlug } from '@/lib/GymSlug'
import { usePublicPlans } from '@/public/HomePage'

export function MembershipPlansPage() {
  const slug = useGymSlug()
  const plans = usePublicPlans()
  return (
    <main className="mx-auto max-w-6xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">Membership plans</h1>
      <p className="mt-3 text-white/60">Prices come from the gym’s live plan list. Nothing here is invented for the brochure.</p>
      <div className="mt-10 grid gap-4 md:grid-cols-3">
        {(plans.data ?? []).map((plan) => (
          <article key={plan.id} className="flex flex-col border border-white/10 p-6">
            <h2 className="text-2xl font-extrabold">{plan.name}</h2>
            <p className="mt-2 flex-1 text-sm text-white/60">{plan.description || 'Ask reception what’s included.'}</p>
            <div className="mt-6 text-3xl font-extrabold text-[#c8f542]">{money(plan.price, plan.currency)}</div>
            <div className="text-sm text-white/50">{plan.durationDays} days</div>
            <Link to={gymPath(slug, '/contact')} className="mt-6 text-sm font-bold text-[#c8f542]">
              Enquire
            </Link>
          </article>
        ))}
      </div>
      {plans.data?.length === 0 ? <p className="mt-6 text-white/50">No active plans yet.</p> : null}
    </main>
  )
}
