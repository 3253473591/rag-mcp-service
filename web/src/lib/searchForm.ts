import type {
  ChunkStrategy,
  SearchRequestPayload,
  SearchStrategy,
} from '../api/types'
import { todayIso } from '../lib/format'

export interface SearchFormState {
  query: string
  topK: number
  strategy: SearchStrategy
  chunkStrategy: ChunkStrategy
  asOf: string
  includeSuperseded: boolean
}

export function defaultSearchForm(): SearchFormState {
  return {
    query: '',
    topK: 5,
    strategy: 'hybrid',
    chunkStrategy: 'heading',
    asOf: todayIso(),
    includeSuperseded: false,
  }
}

export function formFromPayload(
  payload: SearchRequestPayload | null,
): SearchFormState {
  if (!payload) return defaultSearchForm()
  return {
    query: payload.query,
    topK: payload.topK,
    strategy: payload.strategy,
    chunkStrategy: payload.chunkStrategy,
    asOf: payload.asOf,
    includeSuperseded: payload.includeSuperseded,
  }
}

export function toSearchPayload(form: SearchFormState): SearchRequestPayload {
  return {
    query: form.query.trim(),
    topK: form.topK,
    strategy: form.strategy,
    chunkStrategy: form.chunkStrategy,
    asOf: form.asOf || todayIso(),
    includeSuperseded: form.includeSuperseded,
  }
}
