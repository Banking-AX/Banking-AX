export type ApiError = { code: string; message: string; retryable: boolean; traceId: string }
export type ApiEnvelope<T> = { requestId: string; taskCode: string; workItemId: number | null; caseId: number | null; revision: number | null; data: T | null; error: ApiError | null }

const baseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T>> {
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...init.headers } })
  const body = await response.json() as ApiEnvelope<T>
  if (!response.ok || body.error) throw new Error(body.error?.code ?? 'API_ERROR')
  return body
}
