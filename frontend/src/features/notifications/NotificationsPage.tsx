import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { notificationApi, type NotificationItem, type NotificationSummary } from '../../api/notificationApi'
import { c02Api, taskBffEnabled } from '../../api/taskApi'
import { toUserMessage } from '../../api/errorMessages'
import { badgeText, channelLabel, formatReceivedAt, needsCaseLookup, relatedPath, resolvedTaskCode, taskLabel, toneOf } from './notificationView'
import { setUnreadCount } from './unreadStore'
import './notifications.css'

type Filter = 'all' | 'unread'
const PAGE_SIZE = 20

/** ui-reference 57 (list), 58 (visit items), 59 (empty). Opening the page never marks anything read (NOTI-05). */
export function NotificationsPage() {
  const navigate = useNavigate()
  const [filter, setFilter] = useState<Filter>('all')
  const [items, setItems] = useState<NotificationItem[]>([])
  const [summary, setSummary] = useState<NotificationSummary | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const applySummary = (next: NotificationSummary) => { setSummary(next); setUnreadCount(next.unread) }

  const load = useCallback(async (nextFilter: Filter, beforeId?: number) => {
    if (!taskBffEnabled) return
    setLoading(true); setError(null)
    try {
      const response = await notificationApi.list({ unreadOnly: nextFilter === 'unread', beforeId, limit: PAGE_SIZE })
      const page = response.data
      if (!page) return
      setItems(current => beforeId === undefined ? page.items : [...current, ...page.items])
      setHasMore(page.hasMore)
      applySummary(page.summary)
    } catch (cause) {
      setError(toUserMessage(cause))
    } finally { setLoading(false) }
  }, [])

  useEffect(() => { void load(filter) }, [filter, load])

  // Fallback for notifications that carry a caseId but no taskCode (VISIT_CONFIRMED today): the customer's own
  // C-02 case list decides whether the case links to the C-02 detail page. Loaded once, only when needed.
  // Remove this lookup once the server fills taskCode for every C-02 notification.
  const [c02CaseIds, setC02CaseIds] = useState<ReadonlySet<number> | undefined>(undefined)
  const caseLookupNeeded = items.some(needsCaseLookup)
  useEffect(() => {
    if (!taskBffEnabled || !caseLookupNeeded || c02CaseIds) return
    let active = true
    c02Api.customerCases(`noti-c02-cases-${crypto.randomUUID()}`)
      .then(response => { if (active) setC02CaseIds(new Set((response.data ?? []).map(row => row.caseId))) })
      .catch(() => { if (active) setC02CaseIds(new Set()) })
    return () => { active = false }
  }, [caseLookupNeeded, c02CaseIds])

  const open = async (item: NotificationItem) => {
    setNotice(null)
    if (!item.readAt) {
      try {
        await notificationApi.read(item.id)
        const readAt = new Date().toISOString()
        setItems(current => filter === 'unread' ? current.filter(row => row.id !== item.id) : current.map(row => row.id === item.id ? { ...row, readAt } : row))
        if (summary) applySummary({ ...summary, unread: Math.max(0, summary.unread - 1) })
      } catch (cause) {
        setError(toUserMessage(cause))
        return
      }
    }
    const target = relatedPath(item, c02CaseIds)
    if (target) navigate(target)
    else setNotice('이 알림과 연결된 상세 화면이 아직 없어요. 알림 내용으로 확인해 주세요.')
  }

  const readAll = async () => {
    setNotice(null); setError(null)
    try {
      const response = await notificationApi.readAll()
      if (response.data) applySummary(response.data.summary)
      await load(filter)
    } catch (cause) { setError(toUserMessage(cause)) }
  }

  const unread = summary?.unread ?? 0
  const empty = !loading && !error && items.length === 0

  return <main className="noti-page">
    <div className="noti-head">
      <div><h1>알림</h1><span>업무 처리 결과와 확인 요청, 방문 예약 소식을 최신순으로 보여드려요.</span></div>
      <button type="button" className="noti-read-all" onClick={() => void readAll()} disabled={!taskBffEnabled || unread === 0}>모두 읽음</button>
    </div>

    <div role="tablist" aria-label="알림 필터" className="noti-tabs">
      <button type="button" role="tab" aria-selected={filter === 'all'} className="noti-tab" onClick={() => setFilter('all')}>전체 <span>{summary?.total ?? 0}</span></button>
      <button type="button" role="tab" aria-selected={filter === 'unread'} className="noti-tab" onClick={() => setFilter('unread')}>읽지 않음 <span>{unread}</span></button>
    </div>

    {!taskBffEnabled && <p role="status" className="noti-banner">알림은 로그인 BFF 연결 후 볼 수 있어요.</p>}
    {error && <p role="alert" className="noti-banner noti-banner--error">{error}</p>}
    {notice && <p role="status" className="noti-banner">{notice}</p>}

    {empty ? <section className="noti-empty">
      <svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"></path><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"></path></svg>
      <strong>{filter === 'unread' ? '읽지 않은 알림이 없어요' : '아직 받은 알림이 없어요'}</strong>
      <span>B·C 업무를 진행하면 처리 결과와 확인 요청을 여기서 알려 드려요.</span>
    </section> : <ul className="noti-list">
      {items.map(item => {
        const { tone, icon } = toneOf(item.type)
        const label = taskLabel(resolvedTaskCode(item, c02CaseIds))
        return <li key={item.id}>
          <button type="button" className={`noti-item${item.readAt ? '' : ' noti-item--unread'}`} onClick={() => void open(item)}>
            <span className={`noti-icon noti-icon--${tone}`} aria-hidden="true">{icon}</span>
            <span className="noti-body">
              <span className="noti-title-row">
                {!item.readAt && <span className="noti-dot" aria-label="읽지 않음"></span>}
                <strong>{item.title}</strong>
                {label && <span className="noti-task">{label}</span>}
              </span>
              <span className="noti-content">{item.content}</span>
              <span className="noti-meta">{formatReceivedAt(item.createdAt)} · {channelLabel(item.emailStatus)}</span>
            </span>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#9CA3AF" strokeWidth="2" strokeLinecap="round" aria-hidden="true" className="noti-chevron"><path d="M9 6l6 6-6 6"></path></svg>
          </button>
        </li>
      })}
    </ul>}

    {loading && <p role="status" className="noti-loading">알림을 불러오고 있어요.</p>}
    {hasMore && !loading && <button type="button" className="noti-more" onClick={() => void load(filter, items[items.length - 1]?.id)}>이전 알림 더보기</button>}

    <p className="noti-footnote">알림을 눌러야 읽음으로 바뀌어요(페이지를 여는 것만으로는 바뀌지 않아요). 조회(A) 업무는 알림·이메일을 보내지 않아요. 이 알림은 법적 통지가 아니에요.</p>
    <span className="noti-sr" aria-live="polite">{badgeText(unread) ? `읽지 않은 알림 ${unread}건` : '읽지 않은 알림 없음'}</span>
  </main>
}
