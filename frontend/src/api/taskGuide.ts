import { apiFetchWithoutAuthRetry } from './client'

export type TaskGuideStep = { key: 'CLASSIFY' | 'RETRIEVE' | 'SUMMARIZE'; label: string; status: string; detail: string }
export type TaskGuideCitation = { docId: string; title: string; section: string }
/** FastAPI /api/v1/rag/task-guide. Informational only: no customer data, no side effects. */
export type TaskGuide = {
  matched: boolean; taskCode: string | null; officialName?: string; kind?: string; nextStep?: string
  understood: string | null; summaryMethod?: 'LLM' | 'EXTRACTIVE' | 'NONE'
  bullets: { text: string; citation: number }[]; citations: TaskGuideCitation[]; steps: TaskGuideStep[]
  clarification: string | null; mode?: string
}

export const taskGuideApi = {
  /** Omit taskCode to let the server's intent classifier match the sentence to one of the 6 tasks. */
  async guide(query: string, taskCode?: string): Promise<TaskGuide> {
    // FastAPI returns the guide itself rather than a Spring ApiEnvelope.
    const body = await apiFetchWithoutAuthRetry<never>('/api/v1/rag/task-guide', { method: 'POST', body: JSON.stringify({ query, task_code: taskCode ?? null }) })
    return body as unknown as TaskGuide
  },
}
