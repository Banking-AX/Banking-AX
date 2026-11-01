import { useEffect, useState } from 'react'
import { c02Api, taskBffEnabled, type NotificationItem } from '../api/taskApi'

export function C02NotificationsPage() {
  const [items, setItems] = useState<NotificationItem[]>([]); const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false)
  const load = async () => { if (!taskBffEnabled) return; setBusy(true); setError(null); try { const r = await c02Api.notifications(`c02-notifications-${Date.now()}`); setItems(r.data ?? []) } catch (e) { setError(e instanceof Error ? e.message : '알림을 불러올 수 없습니다.') } finally { setBusy(false) } }
  useEffect(() => { void load() }, [])
  const read = async (id: number) => { try { await c02Api.readNotification(id, `c02-notification-read-${id}`); setItems(xs => xs.map(x => x.id === id ? { ...x, readAt: new Date().toISOString() } : x)) } catch (e) { setError(e instanceof Error ? e.message : '읽음 처리에 실패했습니다.') } }
  return <section><h1>C-02 처리 알림</h1><p>직원의 최종 모의 접수 검토 결과만 표시합니다. Agent 사전점검은 승인 결과가 아닙니다.</p>
    {!taskBffEnabled && <p role="status">고객 세션 BFF 연결 대기 중입니다.</p>}<button type="button" disabled={!taskBffEnabled || busy} onClick={load}>{busy ? '불러오는 중' : '새로고침'}</button>{error && <p role="alert">{error}</p>}
    {items.length === 0 ? <p>표시할 알림이 없습니다.</p> : <ul>{items.map(item => <li key={item.id}><strong>{item.title}</strong><p>{item.content}</p><small>{item.createdAt}</small>{item.readAt ? <span> 읽음</span> : <button type="button" disabled={!taskBffEnabled} onClick={() => read(item.id)}>읽음 처리</button>}</li>)}</ul>}
  </section>
}
