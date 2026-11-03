import type { CustomerTaskHistoryItem } from '../api/taskApi'
import { taskPathForWorkflow } from './taskNavigation'

/** Build re-entry links only from task IDs returned by the authenticated history API. */
export function taskHistoryPath(item: CustomerTaskHistoryItem | null | undefined): string | null {
  if (!item || !Number.isSafeInteger(item.workItemId) || item.workItemId <= 0) return null

  if (item.taskCode === 'INHERITANCE_DEPOSIT' && item.caseId && item.caseId > 0) {
    return `/tasks/c02/cases/${item.caseId}`
  }

  if (item.workItemStatus === 'ACTIVE') {
    return taskPathForWorkflow({ taskCode: item.taskCode, workItemId: item.workItemId, status: item.workItemStatus })
  }

  if (item.workItemStatus === 'DONE' && item.resultAvailable) {
    if (item.taskCode === 'TRANSACTION_SEARCH') return `/tasks/a01/${item.workItemId}?view=result`
    if (item.taskCode === 'HOUSING_SAVINGS_CLOSE') return `/tasks/b02/${item.workItemId}?view=result`
  }

  return null
}
