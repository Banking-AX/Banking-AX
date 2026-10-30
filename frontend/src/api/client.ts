export type ApiErrorBody = { code: string; message: string; retryable: boolean; traceId: string }
export type ApiEnvelope<T> = { requestId: string; taskCode: string; workItemId: number | null; caseId: number | null; revision: number | null; data: T | null; error: ApiErrorBody | null }

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly retryable: boolean
  readonly traceId: string | null

  constructor(code: string, status = 0, message = code, options: { retryable?: boolean; traceId?: string | null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.retryable = options.retryable ?? false
    this.traceId = options.traceId ?? null
  }
}

type UnauthorizedHandler = (error: ApiError) => void
let unauthorizedHandler: UnauthorizedHandler | null = null

/** Called on HTTP 401 with code AUTH_EXPIRED. Routing is the caller's responsibility. */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null) {
  unauthorizedHandler = handler
}

// Same origin by default; the Vite dev proxy forwards /api to Spring or FastAPI.
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? ''

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T>> {
  let response: Response
  try {
    response = await fetch(`${baseUrl}${path}`, { ...init, credentials: 'include', headers: { 'Content-Type': 'application/json', ...init.headers } })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError('NETWORK_ERROR', 0, 'Network request failed.')
  }

  const body = await readJson(response)
  const error = toApiError(response, body)
  if (error) {
    if (error.status === 401 && error.code === 'AUTH_EXPIRED') unauthorizedHandler?.(error)
    throw error
  }
  return body as ApiEnvelope<T>
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text().catch(() => '')
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

// Accepts Spring ApiEnvelope.error, FastAPI {error:{code}} and FastAPI HTTPException {detail:"CODE"}.
function toApiError(response: Response, body: unknown): ApiError | null {
  const status = response.status
  if (isRecord(body)) {
    const error = body.error
    if (isRecord(error) && typeof error.code === 'string') {
      return new ApiError(error.code, status, typeof error.message === 'string' ? error.message : error.code, {
        retryable: error.retryable === true,
        traceId: typeof error.traceId === 'string' ? error.traceId : null,
      })
    }
    if (!response.ok && typeof body.detail === 'string') {
      const code = /^([A-Z][A-Z0-9_]+)(?::|$)/.exec(body.detail)?.[1] ?? 'HTTP_ERROR'
      return new ApiError(code, status, body.detail)
    }
  }
  if (!response.ok) return new ApiError('HTTP_ERROR', status, `HTTP ${status}`)
  if (body === undefined) return new ApiError('INVALID_RESPONSE', status, 'Response body is not valid JSON.')
  return null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
