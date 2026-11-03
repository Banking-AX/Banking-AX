import { describe, expect, it } from 'vitest'
import { taskHistoryPath } from './taskHistoryNavigation'
import type { CustomerTaskHistoryItem } from '../api/taskApi'

const historyItem = (overrides: Partial<CustomerTaskHistoryItem>): CustomerTaskHistoryItem => ({
  taskCode: 'TRANSACTION_SEARCH', workItemId: 71, workItemStatus: 'ACTIVE',
  workflowStatus: 'RUNNING', caseId: null, caseStatus: null, resultAvailable: false,
  ...overrides,
})

describe('task history re-entry paths', () => {
  it('routes active work through the existing workflow helper', () => {
    expect(taskHistoryPath(historyItem({ taskCode: 'HOUSING_SAVINGS_CLOSE', workItemId: 72 })))
      .toBe('/tasks/b02/72')
  })

  it('opens completed A/B results in view-only mode', () => {
    expect(taskHistoryPath(historyItem({ workItemStatus: 'DONE', resultAvailable: true })))
      .toBe('/tasks/a01/71?view=result')
    expect(taskHistoryPath(historyItem({ taskCode: 'HOUSING_SAVINGS_CLOSE', workItemStatus: 'DONE', resultAvailable: true })))
      .toBe('/tasks/b02/71?view=result')
  })

  it('resumes C-02 by the Case ID returned by the server', () => {
    expect(taskHistoryPath(historyItem({ taskCode: 'INHERITANCE_DEPOSIT', workItemId: 73, caseId: 91,
      workItemStatus: 'HANDED_OFF', resultAvailable: true }))).toBe('/tasks/c02/cases/91')
  })

  it('does not invent a route for missing cases or unavailable completed results', () => {
    expect(taskHistoryPath(historyItem({ taskCode: 'INHERITANCE_DEPOSIT', caseId: null }))).toBe('/tasks/c02/71')
    expect(taskHistoryPath(historyItem({ workItemStatus: 'DONE', resultAvailable: false }))).toBeNull()
    expect(taskHistoryPath(null)).toBeNull()
  })
})
