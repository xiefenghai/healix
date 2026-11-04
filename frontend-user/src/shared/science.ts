import pepperColors from '../assets/science/PEPPER_COLORS.mp4'
import { api } from '../api/http'
import { currentPatientIdFromToken } from './patient-context'

/** 编码与后端 ScienceCatalog 一一对应。 */
const VIDEOS: Record<string, string> = {
  PEPPER_COLORS: pepperColors,
}

export type ScienceClip = {
  code: string
  title: string
  watched: boolean
  watchedBizDate?: string | null
}

export type ScienceToday = {
  enabled: boolean
  eligible: boolean
  opened: boolean
  watched: boolean
  replay: boolean
  holdText?: string | null
  disclaimer?: string | null
  clip?: ScienceClip | null
}

export type ScienceOverview = {
  enabled: boolean
  watchedCount: number
  totalCount: number
  disclaimer?: string | null
  canOpen: boolean
  replay: boolean
  holdText?: string | null
  today?: ScienceClip | null
  clips: ScienceClip[]
}

export function scienceVideo(code?: string | null): string | null {
  if (!code) return null
  return VIDEOS[code] || null
}

export function sciencePoster(code?: string | null): string | null {
  const src = scienceVideo(code)
  return src ? `${src}#t=0.1` : null
}

function todayKey(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function dismissKey(): string | null {
  const peopleId = currentPatientIdFromToken()
  if (!peopleId) return null
  return `healix_c_science_dismiss:${peopleId}:${todayKey()}`
}

export function scienceDismissedToday(): boolean {
  const key = dismissKey()
  if (!key) return false
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

export function markScienceDismissedToday() {
  const key = dismissKey()
  if (!key) return
  try {
    localStorage.setItem(key, '1')
  } catch {
    /* ignore */
  }
}

export async function loadScienceToday(): Promise<ScienceToday | null> {
  try {
    const res = await api<{ data: ScienceToday }>('/api/c/v1/science/today')
    return res.data || null
  } catch {
    return null
  }
}

export async function loadScienceOverview(): Promise<ScienceOverview | null> {
  try {
    const res = await api<{ data: ScienceOverview }>('/api/c/v1/science')
    return res.data || null
  } catch {
    return null
  }
}

export async function openScienceToday(): Promise<ScienceToday> {
  const res = await api<{ data: ScienceToday }>('/api/c/v1/science/open', { method: 'POST' })
  return res.data
}

export async function watchScienceToday(): Promise<ScienceToday> {
  const res = await api<{ data: ScienceToday }>('/api/c/v1/science/watch', { method: 'POST' })
  return res.data
}

/**
 * 打卡后的引导：今天还没看、本机没关掉时，先确定今日这一条（带标题）再弹出。
 * 接口不可用或今天还不能看时返回 null，不打断打卡。
 */
export async function claimScienceOffer(): Promise<ScienceToday | null> {
  const today = await loadScienceToday()
  if (!today?.enabled || today.watched || scienceDismissedToday()) return null
  if (today.opened && today.clip) return today
  if (!today.eligible) return null
  try {
    const opened = await openScienceToday()
    if (!opened?.clip || opened.watched) return null
    return opened
  } catch {
    return null
  }
}
