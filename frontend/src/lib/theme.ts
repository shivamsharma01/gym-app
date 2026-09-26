export const THEME_STORAGE_KEY = 'gym-theme'

export type ThemeMode = 'dark' | 'light' | 'slate'

export const THEME_OPTIONS: { id: ThemeMode; label: string; blurb: string }[] = [
  { id: 'dark', label: 'Dark', blurb: 'Lime accent on charcoal' },
  { id: 'light', label: 'Light', blurb: 'Green accent on paper' },
  { id: 'slate', label: 'Slate', blurb: 'Cyan accent on cool gray' },
]

export function readStoredTheme(): ThemeMode {
  try {
    const v = localStorage.getItem(THEME_STORAGE_KEY)
    if (v === 'light' || v === 'slate' || v === 'dark') return v
  } catch {
    // ignore
  }
  return 'dark'
}

export function applyTheme(mode: ThemeMode) {
  document.documentElement.dataset.theme = mode
  try {
    localStorage.setItem(THEME_STORAGE_KEY, mode)
  } catch {
    // ignore quota / private mode
  }
}

export function cycleTheme(): ThemeMode {
  const order: ThemeMode[] = ['dark', 'light', 'slate']
  const i = order.indexOf(readStoredTheme())
  const next = order[(i + 1) % order.length]
  applyTheme(next)
  return next
}

/** @deprecated Prefer cycleTheme for three modes */
export function toggleTheme(): ThemeMode {
  return cycleTheme()
}
