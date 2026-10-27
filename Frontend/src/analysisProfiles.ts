import type { Profile } from './api'

export type AnalysisSavedProfile = Profile & { id: string; savedAt: string }
const ANALYSIS_PROFILE_KEY = 'wooaeyoung.profiles'

export function loadAnalysisProfiles(): AnalysisSavedProfile[] {
  try { return JSON.parse(localStorage.getItem(ANALYSIS_PROFILE_KEY) ?? '[]') as AnalysisSavedProfile[] } catch { return [] }
}

export function saveAnalysisProfile(profile: Profile, id: string = crypto.randomUUID()): AnalysisSavedProfile {
  const next: AnalysisSavedProfile = { ...profile, id, savedAt: new Date().toISOString() }
  const profiles = loadAnalysisProfiles()
  const exists = profiles.some(item => item.id === id)
  localStorage.setItem(ANALYSIS_PROFILE_KEY, JSON.stringify(exists ? profiles.map(item => item.id === id ? next : item) : [next, ...profiles]))
  window.dispatchEvent(new CustomEvent('wooaeyoung:profiles-changed'))
  return next
}
