import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { toUserMessage } from '../api/errorMessages'
import { createMockWorkRecordsAdapter } from '../api/mock/workRecordsMock'
import type { WorkRecordDetail } from '../api/workRecords'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { WorkRecordDetailView } from '../features/work/WorkRecordDetailView'

type State = { kind: 'loading' } | { kind: 'ready'; detail: WorkRecordDetail } | { kind: 'notFound' } | { kind: 'error'; message: string }

export function WorkRecordDetailPage() {
  const { recordId } = useParams()
  // Replace with the server-backed adapter when the work record API exists.
  const adapter = useMemo(() => createMockWorkRecordsAdapter(), [])
  const [state, setState] = useState<State>({ kind: 'loading' })

  const load = useCallback(() => {
    let active = true
    setState({ kind: 'loading' })
    if (!recordId) { setState({ kind: 'notFound' }); return () => { active = false } }
    adapter.getDetail(recordId)
      .then(detail => { if (active) setState({ kind: 'ready', detail }) })
      .catch(cause => {
        if (!active) return
        if (cause instanceof ApiError && cause.code === 'WORK_RECORD_NOT_FOUND') setState({ kind: 'notFound' })
        else setState({ kind: 'error', message: toUserMessage(cause) })
      })
    return () => { active = false }
  }, [adapter, recordId])

  useEffect(() => load(), [load])

  // Move focus to the page heading once the record (or the not-found notice) is shown.
  useEffect(() => {
    if (state.kind === 'ready' || state.kind === 'notFound') document.getElementById('work-detail-title')?.focus()
  }, [state.kind])

  return <div className="work-page">
    <SiteHeader />
    {state.kind === 'ready' ? <WorkRecordDetailView detail={state.detail} /> : <main className="work-main">
      {state.kind === 'loading' && <p role="status" className="work-status-text">업무 기록을 불러오고 있어요.</p>}
      {state.kind === 'notFound' && <section className="work-missing">
        <h1 id="work-detail-title" tabIndex={-1} className="work-title">찾을 수 없는 업무 기록이에요.</h1>
        <Link to="/work" className="work-button work-button--plain">목록으로</Link>
      </section>}
      {state.kind === 'error' && <div role="alert" className="work-alert">
        <span className="work-alert__text">{state.message}</span>
        <button type="button" onClick={() => load()} className="work-alert__retry">다시 시도</button>
      </div>}
    </main>}
    <SiteFooter />
  </div>
}
