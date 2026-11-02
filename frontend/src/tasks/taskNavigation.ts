/**
 * Maps a server-issued Workflow/WorkItem result to a customer task route.
 * This helper does not start work or infer IDs; callers must pass the result
 * returned by the authenticated Chat/Workflow contract.
 */
export type WorkflowTaskTarget = { taskCode: string; workItemId: number; status: string }

const TASK_PATHS: Record<string, string> = {
  TRANSACTION_SEARCH: '/tasks/a01',
  HOUSING_SAVINGS_CLOSE: '/tasks/b02',
  INHERITANCE_DEPOSIT: '/tasks/c02',
}

export function taskPathForWorkflow(target: WorkflowTaskTarget | null | undefined): string | null {
  if (!target || target.status !== 'ACTIVE' || !Number.isSafeInteger(target.workItemId) || target.workItemId <= 0) return null
  const base = TASK_PATHS[target.taskCode]
  return base ? `${base}/${target.workItemId}` : null
}
