import { reactive } from 'vue'
import { isAbortError } from '../api/client'
import { searchEvidence } from '../api/search'
import type { SearchRequestPayload, SearchResponse } from '../api/types'
import { toProblem, type ProblemState } from '../lib/errors'

export type SearchStatus = 'idle' | 'loading' | 'success' | 'error'

interface SearchState {
  status: SearchStatus
  payload: SearchRequestPayload | null
  response: SearchResponse | null
  error: ProblemState | null
  /** Increments on every successful response; used to key the entrance animation. */
  runId: number
}

export const searchState = reactive<SearchState>({
  status: 'idle',
  payload: null,
  response: null,
  error: null,
  runId: 0,
})

let controller: AbortController | null = null

export const searchStore = {
  async run(payload: SearchRequestPayload): Promise<void> {
    controller?.abort()
    controller = new AbortController()
    const signal = controller.signal

    searchState.status = 'loading'
    searchState.payload = payload
    searchState.response = null
    searchState.error = null

    try {
      const response = await searchEvidence(payload, signal)
      if (signal.aborted) return
      searchState.response = response
      searchState.status = 'success'
      searchState.runId += 1
    } catch (error) {
      if (isAbortError(error) || signal.aborted) return
      searchState.error = toProblem(error, '检索过程中发生了未预期的错误')
      searchState.status = 'error'
    }
  },

  clear(): void {
    controller?.abort()
    controller = null
    searchState.status = 'idle'
    searchState.payload = null
    searchState.response = null
    searchState.error = null
  },
}
