import { afterEach, describe, expect, it, vi } from 'vitest'
import { needsReview, policyChangeApi, taskLabel, type ChangeView } from './policyChangeApi'

const envelope = (data: unknown) => JSON.stringify({ requestId: 'r', data, error: null })

afterEach(() => vi.unstubAllGlobals())

describe('policyChangeApi', () => {
  it('sends the staff decision to the server and leaves the actor to the JWT', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => new Response(envelope({ decision: 'NO_ACTION' }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await policyChangeApi.decide(7, 'NO_ACTION', '  확인함  ')

    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/staff/policy-changes/7/decision')
    const body = JSON.parse(String(fetchMock.mock.calls[0][1]?.body))
    expect(body).toMatchObject({ decision: 'NO_ACTION', note: '확인함' })
    expect(body).not.toHaveProperty('staffId')
  })

  it('encodes the task code of the notice lookup', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) => new Response(envelope({ approved: [], pending: [] }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await policyChangeApi.notices('A B')

    expect(String(fetchMock.mock.calls[0][0])).toContain('taskCode=A%20B')
    expect(String(fetchMock.mock.calls[0][0])).not.toContain('caseId')

    await policyChangeApi.notices('A', 2)

    expect(String(fetchMock.mock.calls[1][0])).toContain('caseId=2')
  })

  it('treats only an undecided latest version as needing review', () => {
    expect(needsReview({ reviewStatus: 'PENDING_REVIEW' } as ChangeView)).toBe(true)
    expect(needsReview({ reviewStatus: 'SUPERSEDED' } as ChangeView)).toBe(false)
  })

  it('shows an unknown task code as it arrived', () => {
    expect(taskLabel('INHERITANCE_DEPOSIT')).toBe('C-02 상속예금 접수')
    expect(taskLabel('FX_REMITTANCE')).toBe('FX_REMITTANCE')
  })
})
