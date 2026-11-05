import { useSyncExternalStore } from 'react'
import { notificationApi } from '../../api/notificationApi'

// Shared unread count for the header badge and the notification page (NOTI-04/16). No push; callers refresh.
let unread: number | null = null
const listeners = new Set<() => void>()

export function setUnreadCount(value: number | null) {
  if (unread === value) return
  unread = value
  listeners.forEach(listener => listener())
}

export async function refreshUnreadCount() {
  try {
    const response = await notificationApi.summary()
    setUnreadCount(response.data?.unread ?? null)
  } catch {
    // A failed badge refresh must not break navigation; hide the badge instead of showing a stale number.
    setUnreadCount(null)
  }
}

export function useUnreadCount(): number | null {
  return useSyncExternalStore(listener => { listeners.add(listener); return () => { listeners.delete(listener) } }, () => unread, () => null)
}
