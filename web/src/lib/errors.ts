import { ApiError, ApiTransportError } from '../api/client'

export interface ProblemState {
  kind: 'api' | 'transport' | 'unknown'
  code: string
  message: string
  traceId: string
  status?: number
  detail?: string
}

/** Normalise fetch/XHR failures into one shape for ProblemPanel and inline errors. */
export function toProblem(
  error: unknown,
  fallbackMessage = '请求没有完成',
): ProblemState {
  if (error instanceof ApiError) {
    return {
      kind: 'api',
      code: error.code,
      message: error.message,
      traceId: error.traceId,
      status: error.status,
    }
  }
  if (error instanceof ApiTransportError) {
    return {
      kind: 'transport',
      code: 'ERR_NETWORK',
      message: '无法连接到服务',
      traceId: '—',
      detail: error.message,
    }
  }
  return {
    kind: 'unknown',
    code: 'ERR_UNKNOWN',
    message: fallbackMessage,
    traceId: '—',
    detail: error instanceof Error ? error.message : String(error),
  }
}
