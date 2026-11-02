import { describe, expect, it } from 'vitest'
import { taskPathForWorkflow } from './taskNavigation'

describe('task route handoff from a server-issued WorkItem', () => {
  it.each([
    ['TRANSACTION_SEARCH', 101, 'ACTIVE', '/tasks/a01/101'],
    ['HOUSING_SAVINGS_CLOSE', 202, 'ACTIVE', '/tasks/b02/202'],
    ['INHERITANCE_DEPOSIT', 303, 'ACTIVE', '/tasks/c02/303'],
  ])('maps %s to its T2 task page', (taskCode, workItemId, status, expected) => {
    expect(taskPathForWorkflow({ taskCode, workItemId, status })).toBe(expected)
  })

  it('does not route unsupported workflows or invalid IDs', () => {
    expect(taskPathForWorkflow({ taskCode: 'LOAN_INQUIRY', workItemId: 11, status: 'ACTIVE' })).toBeNull()
    expect(taskPathForWorkflow({ taskCode: 'INHERITANCE_DEPOSIT', workItemId: 0, status: 'ACTIVE' })).toBeNull()
    expect(taskPathForWorkflow({ taskCode: 'INHERITANCE_DEPOSIT', workItemId: 303, status: 'DONE' })).toBeNull()
    expect(taskPathForWorkflow(null)).toBeNull()
  })
})
