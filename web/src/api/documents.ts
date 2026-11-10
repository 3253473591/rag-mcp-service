import {
  ApiError,
  ApiTransportError,
  requestJson,
  type ApiErrorBody,
} from './client'
import type {
  DocumentListResponse,
  UploadMetadata,
  UploadResponse,
} from './types'

export function listDocuments(
  limit: number,
  offset: number,
): Promise<DocumentListResponse> {
  const params = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  })
  return requestJson<DocumentListResponse>(`/api/v1/documents?${params}`)
}

export function deleteDocument(docId: string): Promise<void> {
  return requestJson<void>(`/api/v1/documents/${encodeURIComponent(docId)}`, {
    method: 'DELETE',
  })
}

function parseErrorBody(xhr: XMLHttpRequest): ApiErrorBody | null {
  if (!xhr.responseText) return null
  try {
    const value = JSON.parse(xhr.responseText) as unknown
    if (typeof value !== 'object' || value === null) return null
    const candidate = value as Record<string, unknown>
    if (
      typeof candidate.code === 'string' &&
      typeof candidate.message === 'string' &&
      typeof candidate.traceId === 'string'
    ) {
      return candidate as unknown as ApiErrorBody
    }
    return null
  } catch {
    return null
  }
}

export interface UploadHandlers {
  onProgress?: (percent: number | null) => void
  signal?: AbortSignal
}

/**
 * Multipart upload via XMLHttpRequest because fetch still cannot report upload
 * progress in current browsers. Fields mirror DocumentController#upload.
 */
export function uploadDocument(
  metadata: UploadMetadata,
  handlers: UploadHandlers = {},
): Promise<UploadResponse> {
  const form = new FormData()
  form.append('file', metadata.file)
  if (metadata.title.trim()) form.append('title', metadata.title.trim())
  if (metadata.docKey.trim()) form.append('docKey', metadata.docKey.trim())
  if (metadata.version.trim()) form.append('version', metadata.version.trim())
  if (metadata.effectiveFrom) {
    form.append('effectiveFrom', metadata.effectiveFrom)
  }
  if (metadata.effectiveTo) form.append('effectiveTo', metadata.effectiveTo)
  for (const strategy of metadata.chunkStrategies) {
    form.append('chunkStrategies', strategy)
  }

  return new Promise<UploadResponse>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/v1/documents')
    xhr.setRequestHeader('Accept', 'application/json')

    const abort = () => xhr.abort()
    if (handlers.signal) {
      if (handlers.signal.aborted) {
        reject(new DOMException('上传已取消', 'AbortError'))
        return
      }
      handlers.signal.addEventListener('abort', abort, { once: true })
    }

    const cleanup = () => {
      handlers.signal?.removeEventListener('abort', abort)
    }

    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && event.total > 0) {
        handlers.onProgress?.(
          Math.min(100, Math.round((event.loaded / event.total) * 100)),
        )
      } else {
        handlers.onProgress?.(null)
      }
    })

    xhr.addEventListener('load', () => {
      cleanup()
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText) as UploadResponse)
        } catch {
          reject(
            new ApiError(xhr.status, {
              code: 'ERR_RESPONSE_NOT_JSON',
              message: '上传成功，但响应不是 JSON',
              traceId: '—',
            }),
          )
        }
        return
      }
      const body = parseErrorBody(xhr)
      if (body) {
        reject(new ApiError(xhr.status, body))
      } else {
        reject(
          new ApiError(xhr.status, {
            code: `HTTP_${xhr.status}`,
            message:
              xhr.statusText ||
              '上传失败，服务未返回可读的错误信息',
            traceId: '—',
          }),
        )
      }
    })

    xhr.addEventListener('error', () => {
      cleanup()
      reject(new ApiTransportError('上传连接中断'))
    })

    xhr.addEventListener('abort', () => {
      cleanup()
      reject(new DOMException('上传已取消', 'AbortError'))
    })

    xhr.send(form)
  })
}
