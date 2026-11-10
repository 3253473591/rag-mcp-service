export function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

/** Local calendar date, not UTC, so the default asOf never drifts across time zones. */
export function todayIso(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`
}

export function formatScore(value: number | null | undefined): string {
  return typeof value === 'number' && Number.isFinite(value)
    ? value.toFixed(4)
    : '—'
}

export function formatMs(value: number): string {
  return Number.isFinite(value) ? String(Math.max(0, Math.round(value))) : '—'
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  return value.length >= 10 ? value.slice(0, 10) : value
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return (
    `${parsed.getFullYear()}-${pad2(parsed.getMonth() + 1)}-${pad2(parsed.getDate())}` +
    ` ${pad2(parsed.getHours())}:${pad2(parsed.getMinutes())}`
  )
}

export function formatRange(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = formatDate(from)
  const end = formatDate(to)
  if (start === '—' && end === '—') return '—'
  return `${start} 至 ${end === '—' ? '长期' : end}`
}

export function statusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'ACTIVE':
      return '现行'
    case 'SUPERSEDED':
      return '已被替代'
    case 'DRAFT':
      return '草稿'
    default:
      return status?.trim() || '未知'
  }
}

export function reasonLabel(reason: string | null | undefined): string {
  switch (reason) {
    case 'NO_MATCH':
      return '未检索到'
    case 'TOO_NOISY':
      return '噪声过多'
    case 'NONE':
      return '正常'
    default:
      return reason?.trim() || '未知原因'
  }
}

export function pageLabel(
  pageNo: number | null,
  pageEnd: number | null,
): string {
  if (pageNo === null) return ''
  if (pageEnd !== null && pageEnd > pageNo) {
    return `第 ${pageNo}–${pageEnd} 页`
  }
  return `第 ${pageNo} 页`
}

export function strategyLabel(strategy: string): string {
  switch (strategy) {
    case 'hybrid':
      return 'hybrid'
    case 'vector':
      return 'vector'
    case 'keyword':
      return 'keyword'
    default:
      return strategy
  }
}
