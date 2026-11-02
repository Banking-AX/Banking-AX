import { useCallback, useEffect, useMemo, useState } from 'react'
import { toUserMessage } from '../api/errorMessages'
import { createMockWorkRecordsAdapter } from '../api/mock/workRecordsMock'
import type { WorkRecordList } from '../api/workRecords'
import { SiteFooter } from '../components/SiteFooter'
import { SiteHeader } from '../components/SiteHeader'
import { WorkRecordsView } from '../features/work/WorkRecordsView'

export function WorkRecordsPage() {
  // Replace with the server-backed adapter when the work record API exists.
  const adapter = useMemo(() => createMockWorkRecordsAdapter(), [])
  const [data, setData] = useState<WorkRecordList | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    let active = true
    setData(null)
    setError(null)
    adapter.list()
      .then(list => { if (active) setData(list) })
      .catch(cause => { if (active) setError(toUserMessage(cause)) })
    return () => { active = false }
  }, [adapter])

  useEffect(() => load(), [load])

  return <div className="work-page">
    <SiteHeader />
    {data ? <WorkRecordsView data={data} /> : <main className="work-main work-main--list">
      {!error && <p role="status" className="work-status-text">업무 요청 기록을 불러오고 있어요.</p>}
      {error && <div role="alert" className="work-alert">
        <span className="work-alert__text">{error}</span>
        <button type="button" onClick={() => load()} className="work-alert__retry">다시 시도</button>
      </div>}
    </main>}
    <SiteFooter />
  </div>
}
