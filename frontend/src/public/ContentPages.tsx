import { Link } from 'react-router'
import {
  brandAbout,
  brandDisplayName,
  brandFacilitiesCopy,
  brandFacilitiesImage,
  brandTrainingCopy,
  brandTrainingImage,
  gymPath,
} from '@/lib/brand'
import { useGymSlug } from '@/lib/GymSlug'
import { usePublicSite } from '@/public/HomePage'

export function AboutPage() {
  const slug = useGymSlug()
  const site = usePublicSite()
  const name = brandDisplayName(site.data)
  return (
    <div className="mx-auto max-w-3xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">About {name}</h1>
      <p className="mt-6 text-lg text-white/70">{brandAbout(site.data)}</p>
      <p className="mt-4 text-white/50">Hours: {site.data?.hours || 'Ask at reception'}</p>
      <Link to={gymPath(slug, '/contact')} className="mt-8 inline-block font-bold text-[#c8f542]">
        Talk to us
      </Link>
    </div>
  )
}

export function ServicesPage() {
  const site = usePublicSite()
  const training = brandTrainingCopy(site.data)
  return (
    <div className="mx-auto max-w-6xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">Services</h1>
      <p className="mt-4 max-w-2xl text-white/60">{training.body}</p>
      <div className="mt-10 grid gap-6 md:grid-cols-2">
        {[
          [training.title, training.body],
          ['Personal coaching', 'Sessions booked against real availability, not a brochure promise.'],
          ['Recovery', 'Space to cool down. No spa fiction.'],
          ['Member access', 'Face terminal at the door. Access follows membership status.'],
        ].map(([title, body]) => (
          <section key={title} className="border border-white/10 p-6">
            <h2 className="text-2xl font-extrabold">{title}</h2>
            <p className="mt-2 text-white/60">{body}</p>
          </section>
        ))}
      </div>
      <img src={brandTrainingImage(site.data)} alt="" className="mt-12 aspect-[21/9] w-full object-cover" />
    </div>
  )
}

export function FacilitiesPage() {
  const site = usePublicSite()
  const facilities = brandFacilitiesCopy(site.data)
  return (
    <div className="mx-auto max-w-6xl px-4 py-16">
      <h1 className="text-4xl font-extrabold">Facilities</h1>
      <p className="mt-4 max-w-2xl text-white/60">{facilities.body}</p>
      <img src={brandFacilitiesImage(site.data)} alt="" className="mt-10 aspect-[21/9] w-full object-cover" />
      <div className="mt-10 grid gap-4 md:grid-cols-3">
        {['Floor', 'Racks', 'Entrance'].map((label) => (
          <div
            key={label}
            className="flex h-40 items-end bg-gradient-to-br from-[#1c2418] to-[#0b0c0b] p-5 font-extrabold"
          >
            {label}
          </div>
        ))}
      </div>
    </div>
  )
}
