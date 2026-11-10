/**
 * Query-term highlighting without v-html: the result content is split into safe
 * text segments in JS and rendered as text nodes + <mark> elements. Server text
 * is never parsed as HTML.
 */

export interface TextSegment {
  text: string
  hit: boolean
}

const CJK_NOISE = [
  '是什么',
  '是多少',
  '有哪些',
  '哪几类',
  '哪两类',
  '什么时候',
  '为什么',
  '哪些',
  '哪个',
  '多少',
  '什么',
  '如何',
  '怎么',
  '怎样',
  '为何',
  '请问',
  '请',
  '吗',
  '呢',
  '吧',
]

const CJK_SEPARATORS = [
  '可以',
  '包括',
  '进行',
  '需要',
  '应该',
  '是否',
  '以及',
  '的',
  '是',
  '了',
  '和',
  '与',
  '及',
  '或',
  '把',
  '被',
  '对',
  '在',
  '为',
]

const EDGE_CONNECTORS = new Set(
  '有是在为的了和与及或把被对'.split(''),
)

const EN_STOPWORDS = new Set([
  'what',
  'which',
  'how',
  'why',
  'when',
  'where',
  'who',
  'whose',
  'whom',
  'is',
  'are',
  'was',
  'were',
  'be',
  'been',
  'being',
  'the',
  'a',
  'an',
  'of',
  'for',
  'to',
  'in',
  'on',
  'at',
  'by',
  'with',
  'and',
  'or',
  'not',
  'no',
  'do',
  'does',
  'did',
  'can',
  'could',
  'should',
  'would',
  'will',
  'shall',
  'may',
  'might',
  'must',
  'from',
  'that',
  'this',
  'these',
  'those',
  'it',
  'its',
  'as',
  'if',
  'than',
  'then',
  'there',
  'their',
])

const PUNCTUATION = /[\s，,。、；;：:？?！!（）()【】[\]“”"'`·…—/\\|<>]+/gu

function normalizeTerm(raw: string): string | null {
  const stripped = raw.replace(/[^\p{L}\p{N}%_]+/gu, '')
  if (!stripped) return null
  let end = stripped.length
  while (end > 0 && EDGE_CONNECTORS.has(stripped.charAt(end - 1))) end -= 1
  const term = stripped.slice(0, end)
  if (!term) return null
  const isAscii = /^[\x00-\x7F]+$/.test(term)
  if (isAscii) {
    const lower = term.toLowerCase()
    if (lower.length < 3 || EN_STOPWORDS.has(lower)) return null
    return lower
  }
  if (term.length < 2 || term.length > 12) return null
  return term
}

/** Deterministic, server-like term extraction; used only for visual emphasis. */
export function queryTerms(query: string): string[] {
  if (!query.trim()) return []
  let marked = query
  for (const noise of [...CJK_NOISE].sort((a, b) => b.length - a.length)) {
    marked = marked.split(noise).join('\n')
  }
  for (const separator of CJK_SEPARATORS) {
    marked = marked.split(separator).join('\n')
  }
  marked = marked.replace(PUNCTUATION, '\n')

  const terms: string[] = []
  for (const raw of marked.split(/\n+/)) {
    const term = normalizeTerm(raw)
    if (term) terms.push(term)
  }
  const whole = normalizeTerm(query)
  if (whole) terms.push(whole)

  return [...new Set(terms)].slice(0, 8)
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function highlightSegments(
  content: string,
  query: string,
): TextSegment[] {
  if (!content) return []
  const terms = queryTerms(query)
  if (terms.length === 0) return [{ text: content, hit: false }]

  const ranges: Array<[number, number]> = []
  for (const term of terms) {
    const pattern = new RegExp(escapeRegExp(term), 'giu')
    let match: RegExpExecArray | null
    while ((match = pattern.exec(content)) !== null) {
      ranges.push([match.index, match.index + term.length])
      if (match.index === pattern.lastIndex) pattern.lastIndex += 1
    }
  }
  if (ranges.length === 0) return [{ text: content, hit: false }]

  ranges.sort((a, b) => a[0] - b[0] || b[1] - a[1])
  const merged: Array<[number, number]> = []
  for (const range of ranges) {
    const previous = merged[merged.length - 1]
    if (previous && range[0] <= previous[1]) {
      previous[1] = Math.max(previous[1], range[1])
    } else {
      merged.push([range[0], range[1]])
    }
  }

  const segments: TextSegment[] = []
  let cursor = 0
  for (const [start, end] of merged) {
    if (start > cursor) {
      segments.push({ text: content.slice(cursor, start), hit: false })
    }
    segments.push({ text: content.slice(start, end), hit: true })
    cursor = end
  }
  if (cursor < content.length) {
    segments.push({ text: content.slice(cursor), hit: false })
  }
  return segments
}
