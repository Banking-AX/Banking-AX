import { useRef, useState, type KeyboardEvent } from 'react'
import { Link } from 'react-router-dom'
import type { WorkRecordList, WorkStatus, WorkTab, WorkTaskType } from '../../api/workRecords'

const TABS: { key: WorkTab; label: string }[] = [
  { key: 'progress', label: '진행 중' }, { key: 'review', label: '검토 중' }, { key: 'done', label: '완료' }, { key: 'closed', label: '종료' },
]
const TYPE_LABELS: Record<WorkTaskType, string> = { A: 'A · 조회', B: 'B · 자동처리', C: 'C · 직원 검토' }
// manual and pending are new statuses that are not in the list design.
const STATUS_LABELS: Record<WorkStatus, string> = {
  hold: '보류', plan: '대기', recv: '접수 완료 · AI 자동 점검 중', ok: '진행 가능', done: '처리 완료', lack: '서류 부족', cancel: '취소',
  manual: '영업점 상담 필요', pending: '처리 대기',
}

const PERIODS = [{ key: '1', label: '최근 1개월', months: 1 }, { key: '3', label: '최근 3개월', months: 3 }, { key: 'all', label: '전체 기간', months: null }] as const
type PeriodKey = typeof PERIODS[number]['key']
const withinPeriod = (iso: string, key: PeriodKey, asOf: string) => {
  const months = PERIODS.find(period => period.key === key)?.months ?? null
  if (months === null) return true
  const from = new Date(asOf); from.setMonth(from.getMonth() - months)
  return Date.parse(iso) >= from.getTime()
}

const pad2 = (value: number) => String(value).padStart(2, '0')
const toDate = (iso: string) => new Date(iso)
const formatDay = (iso: string) => { const d = toDate(iso); return `${pad2(d.getMonth() + 1)}.${pad2(d.getDate())}` }
const formatDayTime = (iso: string) => { const d = toDate(iso); return `${formatDay(iso)} ${pad2(d.getHours())}:${pad2(d.getMinutes())}` }
const formatStamp = (iso: string) => { const d = toDate(iso); return `${d.getFullYear()}.${formatDayTime(iso)}` }
const detailActionLabel = (status: WorkStatus) => status === 'pending' ? '처리 현황 보기' : status === 'manual' ? '상담 안내 보기' : '결과 보기'

export function WorkRecordsView({ data }: { data: WorkRecordList }) {
  const [tab, setTab] = useState<WorkTab>('progress')
  const [type, setType] = useState<'all' | WorkTaskType>('all')
  const [period, setPeriod] = useState<PeriodKey>('3')
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([])

  const visible = data.items.filter(item => (type === 'all' || item.taskType === type) && withinPeriod(item.requestedAt, period, data.asOf))
  const rows = visible.filter(item => item.tab === tab).sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
  const hasNotes = data.retentionDays !== undefined || data.draftResetDays !== undefined

  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = TABS.length - 1
    const next = event.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
      : event.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
        : event.key === 'Home' ? 0 : event.key === 'End' ? last : null
    if (next === null) return
    event.preventDefault()
    setTab(TABS[next].key)
    tabRefs.current[next]?.focus()
  }

  return <main className="work-main work-main--list">
    <div className="work-intro">
      <nav aria-label="현재 위치" className="work-breadcrumb">
        <Link to="/" className="work-breadcrumb__link">홈</Link><span aria-hidden="true">›</span><span aria-current="page" className="work-breadcrumb__current">업무 요청 기록</span>
      </nav>
      <div className="work-intro__row">
        <div className="work-intro__titles">
          <h1 className="work-title work-title--list">업무 요청 기록</h1>
          <p className="work-lead">채팅방과 관계없이 요청한 금융업무의 접수·진행 상황을 한곳에서 확인하세요.</p>
        </div>
        <span className="work-asof">{formatStamp(data.asOf)} 기준 · 새로고침하면 최신 상태를 불러와요</span>
      </div>
    </div>

    {hasNotes && <div className="work-notes">
      {data.retentionDays !== undefined && <span className="work-note"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#1B6B42" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z"></path></svg>대화 원문은 {data.retentionDays}일 후 삭제돼도 업무 기록은 계속 남아요</span>}
      {data.draftResetDays !== undefined && <span className="work-note"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#9A4A00" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path></svg>제출하지 않은 업무는 마지막 활동 후 {data.draftResetDays}일이 지나면 입력 내용이 초기화돼요</span>}
    </div>}

    <section aria-label="업무 목록" className="work-list">
      <div role="tablist" aria-label="상태" className="work-tabs">
        {TABS.map((item, index) => {
          const selected = item.key === tab
          const count = visible.filter(row => row.tab === item.key).length
          return <button key={item.key} ref={element => { tabRefs.current[index] = element }} type="button" role="tab" id={`work-tab-${item.key}`}
            aria-selected={selected} aria-controls={`work-panel-${item.key}`} tabIndex={selected ? 0 : -1}
            onClick={() => setTab(item.key)} onKeyDown={event => onTabKeyDown(event, index)}
            className={`work-tab${selected ? ' work-tab--selected' : ''}`}>
            {item.label} <span className="work-tab__count">{count}</span>
          </button>
        })}
      </div>

      <div role="tabpanel" id={`work-panel-${tab}`} aria-labelledby={`work-tab-${tab}`}>
        <div className="work-filters">
          <div className="work-filter">
            <label htmlFor="work-filter-type" className="work-filter__label">업무 유형</label>
            <select id="work-filter-type" value={type} onChange={event => setType(event.target.value as 'all' | WorkTaskType)} className="work-filter__select">
              <option value="all">전체</option>
              <option value="A">A · 조회</option>
              <option value="B">B · 자동처리</option>
              <option value="C">C · 직원 검토</option>
            </select>
          </div>
          <div className="work-filter">
            <label htmlFor="work-filter-period" className="work-filter__label">요청 기간</label>
            <select id="work-filter-period" value={period} onChange={event => setPeriod(event.target.value as PeriodKey)} className="work-filter__select">
              {PERIODS.map(item => <option key={item.key} value={item.key}>{item.label}</option>)}
            </select>
          </div>
          <span className="work-sort">정렬: 최근 변경일순</span>
        </div>

        <div tabIndex={0} role="region" aria-label="업무 목록 표" className="work-table-scroll">
          <table className="work-table">
            <thead>
              <tr>
                <th scope="col" className="work-th work-th--first">업무명</th>
                <th scope="col" className="work-th">요청한 채팅방</th>
                <th scope="col" className="work-th">요청일</th>
                <th scope="col" className="work-th">최근 변경</th>
                <th scope="col" className="work-th">현재 상태</th>
                <th scope="col" className="work-th work-th--last"><span className="work-sr-only">행동</span></th>
              </tr>
            </thead>
            <tbody>
              {rows.map(item => <tr key={item.recordId}>
                <td className="work-td work-td--first">
                  <div className="work-name">
                    <span className="work-name__line"><span className={`work-type work-type--${item.taskType}`}>{TYPE_LABELS[item.taskType]}</span><strong className="work-name__text">{item.name}</strong></span>
                    {item.no && <span className="work-name__no">{item.no}</span>}
                  </div>
                </td>
                <td className={`work-td work-chat${!item.chatTitle && item.chatExpired ? ' work-chat--expired' : ''}`}>{item.chatTitle ?? (item.chatExpired ? '대화 보관기간 만료' : '-')}</td>
                <td className="work-td work-td--date">{formatDay(item.requestedAt)}</td>
                <td className="work-td work-td--date">{formatDayTime(item.updatedAt)}</td>
                <td className="work-td">
                  <div className="work-state">
                    <span className={`work-status work-status--${item.status}`}>{STATUS_LABELS[item.status]}</span>
                    {item.sub && <span className={`work-state__sub work-state__sub--${item.subTone ?? 'default'}`}>{item.sub}</span>}
                  </div>
                </td>
                <td className="work-td work-td--last">
                  {/* Other task screens (continue, cancel, reapply, chat room) are connected later. */}
                  {item.detailPath && <Link to={item.detailPath} className="work-action" aria-label={`${item.name} ${detailActionLabel(item.status)}`}>{detailActionLabel(item.status)}</Link>}
                </td>
              </tr>)}
            </tbody>
          </table>
        </div>

        {rows.length === 0 && <div className="work-empty">
          <strong className="work-empty__title">이 상태의 업무가 없어요</strong>
          <span className="work-empty__text">AI 금융 도우미에서 업무를 요청하면 여기에 기록돼요.</span>
          <Link to="/chat" className="work-action work-empty__link">AI 금융 도우미 열기</Link>
        </div>}
      </div>
    </section>
  </main>
}
