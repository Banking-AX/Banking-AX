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

type AuthenticationLostHandler = (error: ApiError) => void
type RefreshAccessTokenHandler = () => Promise<string | null>
let accessToken: string | null = null
let authenticationLostHandler: AuthenticationLostHandler | null = null
let refreshAccessTokenHandler: RefreshAccessTokenHandler | null = null
let refreshInFlight: Promise<string | null> | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

export function setAuthenticationLostHandler(handler: AuthenticationLostHandler | null) {
  authenticationLostHandler = handler
}

export function setRefreshAccessTokenHandler(handler: RefreshAccessTokenHandler | null) {
  refreshAccessTokenHandler = handler
}

// Same origin by default; the Vite dev proxy forwards /api to Spring or FastAPI.
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? ''

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T>> {
  const bearerEndpoint = path === '/api/v1/auth/me'
    || path.startsWith('/api/v1/customer/')
    || path.startsWith('/api/v1/staff/')
  return request<T>(path, init, bearerEndpoint, bearerEndpoint)
}

/** Auth bootstrap endpoints use cookies and must never recursively trigger refresh. */
export async function apiFetchWithoutAuthRetry<T>(path: string, init: RequestInit = {}): Promise<ApiEnvelope<T>> {
  return request<T>(path, init, false, false)
}

async function request<T>(path: string, init: RequestInit, attachAccessToken: boolean,
  allowRefresh: boolean): Promise<ApiEnvelope<T>> {
  const headers = new Headers(init.headers)
  // The C-02 document endpoint is multipart. Let the browser add its boundary
  // instead of forcing the JSON content type for FormData requests.
  if (!headers.has('Content-Type') && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (attachAccessToken && accessToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }
  let response: Response
  try {
    response = await fetch(`${baseUrl}${path}`, { ...init, credentials: 'include', headers })
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause
    throw new ApiError('NETWORK_ERROR', 0, 'Network request failed.')
  }

  const body = await readJson(response)
  const error = toApiError(response, body)
  if (error) {
    if (allowRefresh && error.status === 401) {
      // The access token lives only in memory, so a missing one is recoverable like an expired one:
      // the HttpOnly refresh cookie decides whether the session is really over.
      try {
        const replacement = await refreshOnce()
        if (replacement) {
          setAccessToken(replacement)
          return request<T>(path, init, true, false)
        }
      } catch {
        // The shared handler below clears authentication once for every waiting request.
      }
      setAccessToken(null)
      authenticationLostHandler?.(error)
    }
    throw error
  }
  return body as ApiEnvelope<T>
}

/** Binary download behind the same Bearer token (e.g. staff viewing an uploaded original); refreshes once on 401. */
export async function apiFetchBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  const send = () => {
    const headers = new Headers(init.headers)
    if (!headers.has('Content-Type') && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    if (accessToken && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${accessToken}`)
    return fetch(`${baseUrl}${path}`, { ...init, credentials: 'include', headers })
      .catch(() => { throw new ApiError('NETWORK_ERROR', 0, 'Network request failed.') })
  }
  let response = await send()
  if (response.status === 401) {
    const replacement = await refreshOnce().catch(() => null)
    if (replacement) { setAccessToken(replacement); response = await send() }
  }
  if (!response.ok) throw toApiError(response, await readJson(response)) ?? new ApiError('HTTP_ERROR', response.status)
  return response.blob()
}

function refreshOnce(): Promise<string | null> {
  if (!refreshAccessTokenHandler) return Promise.resolve(null)
  if (!refreshInFlight) {
    refreshInFlight = refreshAccessTokenHandler().finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
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
