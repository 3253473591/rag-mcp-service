/** Wire types mirroring com.lqx.ragmcp.api.dto — keep in sync with the Java records. */

export type SearchStrategy = 'hybrid' | 'vector' | 'keyword'
export type ChunkStrategy = 'heading' | 'fixed'

export interface SearchRequestPayload {
  query: string
  topK: number
  strategy: SearchStrategy
  chunkStrategy: ChunkStrategy
  asOf: string
  includeSuperseded: boolean
}

export interface SearchResultItem {
  chunkId: string
  docId: string
  docKey: string
  docVersion: string | null
  docStatus: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  title: string
  clauseId: string | null
  clausePath: string | null
  pageNo: number | null
  pageEnd: number | null
  score: number
  rerankScore: number | null
  content: string
  citation: string
}

export interface AbstainDiagnostics {
  topScore: number
  secondScore: number
  scoreGap: number
  candidateCount: number
  denseCount: number
  minTopScore: number
  highConfidenceTopScore: number
  minDenseCandidates: number
  minScoreGap: number
  enabled: boolean
  reason: string
  candidateScores: number[]
}

export interface SearchResponse {
  query: string
  asOf: string
  chunkStrategy: string
  strategy: string
  tookMs: number
  results: SearchResultItem[]
  conflicts: unknown[]
  abstained: boolean
  abstainReason: string | null
  message: string | null
  diagnostics: AbstainDiagnostics | null
}

export interface DocumentSummary {
  docId: string
  docKey: string
  title: string
  sourceType: string
  version: string | null
  status: string
  effectiveFrom: string | null
  effectiveTo: string | null
  chunkCount: number
  createdAt: string
}

export interface DocumentListResponse {
  items: DocumentSummary[]
  total: number
  limit: number
  offset: number
}

export interface UploadResponse {
  docId: string
  docKey: string
  version: string | null
  title: string
  sourceType: string
  status: string
  effectiveFrom: string | null
  effectiveTo: string | null
  chunkStrategies: string[]
  chunkCount: number
  chunkCounts: Record<string, number>
  duplicate: boolean
  tookMs: number
}

export interface UploadMetadata {
  file: File
  title: string
  docKey: string
  version: string
  effectiveFrom: string
  effectiveTo: string
  chunkStrategies: ChunkStrategy[]
}
