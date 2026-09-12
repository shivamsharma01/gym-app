import { Link } from 'react-router'
import { usePublicSite } from '@/public/HomePage'

export function AboutPage() {
  const site = usePublicSite()
  return (
    <main className="mx-auto max-w-3xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">About</h1>
      <p className="mt-6 text-lg text-white/70">
        {site.data?.about ||
          'True Gym is a training space first. Membership, payments, and door access are one record — so the floor stays for people who belong here.'}
      </p>
      <p className="mt-4 text-white/50">Hours: {site.data?.hours || 'Ask at reception'}</p>
      <Link to="/contact" className="mt-8 inline-block font-bold text-[#c8f542]">
        Talk to us
      </Link>
    </main>
  )
}

export function ServicesPage() {
  return (
    <main className="mx-auto max-w-6xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">Services</h1>
      <div className="mt-10 grid gap-6 md:grid-cols-2">
        {[
          ['Strength & conditioning', 'Barbell, machines, and programmed blocks — not random circuits.'],
          ['Personal coaching', 'Sessions booked against real availability, not a brochure promise.'],
          ['Recovery', 'Space to cool down. No spa fiction.'],
          ['Member access', 'Face terminal at the door. Access follows membership status.'],
        ].map(([title, body]) => (
          <section key={title} className="rounded-3xl border border-white/10 p-6">
            <h2 className="text-2xl font-extrabold">{title}</h2>
            <p className="mt-2 text-white/60">{body}</p>
          </section>
        ))}
      </div>
    </main>
  )
}

export function FacilitiesPage() {
  return (
    <main className="mx-auto max-w-6xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">Facilities</h1>
      <p className="mt-4 max-w-2xl text-white/60">
        Changing rooms, a serious free-weight floor, and an entrance terminal. Photos below are layout placeholders, not stock people.
      </p>
      <div className="mt-10 grid gap-4 md:grid-cols-3">
        {['Floor', 'Racks', 'Entrance'].map((label) => (
          <div
            key={label}
            className="flex h-52 items-end rounded-3xl bg-gradient-to-br from-[#1c2418] to-[#0b0c0b] p-5 font-extrabold"
          >
            {label}
          </div>
        ))}
      </div>
    </main>
  )
}
