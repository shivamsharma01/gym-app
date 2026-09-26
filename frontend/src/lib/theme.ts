export const THEME_STORAGE_KEY = 'gym-theme'

export type ThemeMode = 'dark' | 'light'

export function readStoredTheme(): ThemeMode {
  try {
    const v = localStorage.getItem(THEME_STORAGE_KEY)
    return v === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

export function applyTheme(mode: ThemeMode) {
  document.documentElement.dataset.theme = mode
  try {
    localStorage.setItem(THEME_STORAGE_KEY, mode)
  } catch {
    // ignore quota / private mode
  }
}

export function toggleTheme(): ThemeMode {
  const next: ThemeMode = readStoredTheme() === 'light' ? 'dark' : 'light'
  applyTheme(next)
  return next
}
