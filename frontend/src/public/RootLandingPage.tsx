import { Link } from 'react-router'

/** Root landing when no gym slug is in the URL. Gyms are enrolled by SUPER_ADMIN. */
export function RootLandingPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-[#0b0c0b] px-4 text-center text-[#f4f1ea]">
      <h1 className="text-4xl font-extrabold tracking-tight md:text-5xl">Gym platform</h1>
      <p className="mt-4 max-w-md text-white/60">
        Public gym sites live at <code className="text-[#c8f542]">/g/your-gym-slug</code> after a
        platform super-admin enrolls the gym. No demo gym is pre-created.
      </p>
      <div className="mt-10 flex flex-wrap justify-center gap-3">
        <Link
          to="/app/login"
          className="rounded-full bg-[#c8f542] px-6 py-3 font-bold text-[#14180f] transition hover:brightness-110"
        >
          Staff sign in
        </Link>
      </div>
    </main>
  )
}
