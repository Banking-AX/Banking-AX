import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../api/client'
import { getCustomerTaskHistory, taskBffEnabled, type CustomerTaskHistoryItem } from '../api/taskApi'
import { taskHistoryPath } from '../tasks/taskHistoryNavigation'

const TASK_LABELS: Record<CustomerTaskHistoryItem['taskCode'], string> = {
  TRANSACTION_SEARCH: '거래내역 조회',
  HOUSING_SAVINGS_CLOSE: '청약통장 모의 해지',
  INHERITANCE_DEPOSIT: '상속예금 사전접수',
}

export function CustomerTaskHistoryPage() {
  const [items, setItems] = useState<CustomerTaskHistoryItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let active = true
    if (!taskBffEnabled) {
      setError('업무 이력은 인증된 업무 BFF를 사용할 수 있을 때 조회됩니다.')
      setLoading(false)
      return
    }
    getCustomerTaskHistory().then(response => {
      if (!active) return
      setItems(response.data ?? [])
      setError(null)
    }).catch(cause => {
      if (!active) return
      setError(cause instanceof ApiError ? cause.message : '업무 이력을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [reload])

  return <section>
    <h1>내 업무 이력</h1>
    {loading && <p role="status">업무 이력을 불러오고 있습니다.</p>}
    {error && <p role="alert">{error}</p>}
    {error && taskBffEnabled && <button type="button" disabled={loading} onClick={() => { setLoading(true); setReload(value => value + 1) }}>다시 불러오기</button>}
    {!loading && !error && items.length === 0 && <p role="status">다시 확인할 업무가 없습니다.</p>}
    {!loading && !error && items.length > 0 && <ul>
      {items.map(item => {
        const path = taskHistoryPath(item)
        return <li key={item.workItemId}>
          <h2>{TASK_LABELS[item.taskCode]}</h2>
          <p>업무 상태: {item.workItemStatus} · Workflow 상태: {item.workflowStatus}</p>
          {item.caseId && <p>Case 상태: {item.caseStatus ?? '확인 필요'}</p>}
          {path
            ? <Link to={path}>{item.workItemStatus === 'DONE' && item.taskCode !== 'INHERITANCE_DEPOSIT' ? '저장 결과 보기' : '업무 이어하기'}</Link>
            : <p>저장 결과를 조회할 수 없습니다. 이력은 보존되어 있지만 화면 연결이 필요하면 고객센터에 문의해 주세요.</p>}
        </li>
      })}
    </ul>}
  </section>
}
