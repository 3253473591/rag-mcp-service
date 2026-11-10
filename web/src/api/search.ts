import { requestJson } from './client'
import type { SearchRequestPayload, SearchResponse } from './types'

export function searchEvidence(
  payload: SearchRequestPayload,
  signal?: AbortSignal,
): Promise<SearchResponse> {
  return requestJson<SearchResponse>('/api/v1/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal,
  })
}
