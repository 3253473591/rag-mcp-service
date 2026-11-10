export interface ApiErrorBody {
  code: string
  message: string
  traceId: string
  details?: Record<string, unknown>
}

/** A structured error returned by the backend: {code, message, traceId, details?}. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string
  readonly details?: Record<string, unknown>

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
    this.traceId = body.traceId
    this.details = body.details
  }
}

/** The request never reached the backend, or the connection dropped. */
export class ApiTransportError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ApiTransportError'
  }
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException
    ? error.name === 'AbortError'
    : error instanceof Error && error.name === 'AbortError'
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.code === 'string' &&
    typeof candidate.message === 'string' &&
    typeof candidate.traceId === 'string'
  )
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return text
  }
}

export async function requestJson<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init?.headers ?? {}),
      },
    })
  } catch (error) {
    if (isAbortError(error)) throw error
    const detail = error instanceof Error ? error.message : String(error)
    throw new ApiTransportError(detail)
  }

  const body = await readBody(response)

  if (!response.ok) {
    if (isApiErrorBody(body)) {
      throw new ApiError(response.status, body)
    }
    throw new ApiError(response.status, {
      code: `HTTP_${response.status}`,
      message:
        typeof body === 'string' && body.trim()
          ? body.trim().slice(0, 300)
          : '服务返回了非标准错误响应',
      traceId: '—',
    })
  }

  if (response.status === 204) return undefined as T
  if (body === null) return undefined as T
  if (typeof body === 'string') {
    throw new ApiError(response.status, {
      code: 'ERR_RESPONSE_NOT_JSON',
      message: '服务返回的不是 JSON，可能是代理或网关错误页',
      traceId: '—',
    })
  }
  return body as T
}
