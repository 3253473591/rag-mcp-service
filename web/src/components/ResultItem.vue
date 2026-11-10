<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { SearchResultItem } from '../api/types'
import { copyText } from '../lib/clipboard'
import {
  formatDate,
  formatScore,
  pageLabel,
  pad2,
  statusLabel,
} from '../lib/format'
import { highlightSegments } from '../lib/highlight'

const props = defineProps<{
  item: SearchResultItem
  index: number
  query: string
}>()

const segments = computed(() =>
  highlightSegments(props.item.content, props.query),
)

const clauseText = computed(() => {
  if (props.item.clausePath?.trim()) return props.item.clausePath.trim()
  if (props.item.clauseId?.trim()) return props.item.clauseId.trim()
  return '无条款定位'
})

const hasPage = computed(() => props.item.pageNo !== null)
const page = computed(() => pageLabel(props.item.pageNo, props.item.pageEnd))
const isSuperseded = computed(() => props.item.docStatus === 'SUPERSEDED')

const delayStyle = computed(() => ({
  animationDelay: `${props.index * 40}ms`,
}))

type CopyState = 'idle' | 'copied' | 'failed'
const copyState = ref<CopyState>('idle')
const copyAnnouncement = ref('')
let resetTimer: number | undefined

async function onCopy(): Promise<void> {
  const ok = await copyText(props.item.citation)
  copyState.value = ok ? 'copied' : 'failed'
  copyAnnouncement.value = ok
    ? `已复制引用：${props.item.citation}`
    : '复制失败，请手动选择引用文本。'
  window.clearTimeout(resetTimer)
  resetTimer = window.setTimeout(() => {
    copyState.value = 'idle'
  }, 2400)
}

onBeforeUnmount(() => window.clearTimeout(resetTimer))
</script>

<template>
  <article class="result" :style="delayStyle">
    <div class="result__rail" aria-hidden="true">
      <span class="result__rank mono">{{ pad2(index + 1) }}</span>
    </div>

    <div class="result__main">
      <h3 class="result__head">
        <span class="result__title">《{{ item.title }}》</span>
        <span v-if="item.docVersion" class="result__version mono"
          >v{{ item.docVersion }}</span
        >
        <span class="result__sep" aria-hidden="true">·</span>
        <span class="result__clause">{{ clauseText }}</span>
        <template v-if="hasPage">
          <span class="result__sep" aria-hidden="true">·</span>
          <span class="result__page mono">{{ page }}</span>
        </template>
      </h3>

      <p class="result__doc mono">
        <span>{{ item.docKey }}</span>
        <span v-if="isSuperseded" class="result__status">{{
          statusLabel(item.docStatus)
        }}</span>
        <span v-if="item.effectiveFrom"
          >生效 {{ formatDate(item.effectiveFrom) }}</span
        >
        <span v-if="item.effectiveTo"
          >失效 {{ formatDate(item.effectiveTo) }}</span
        >
      </p>

      <blockquote class="quote" :aria-label="`引用原文：${item.citation}`">
        <p class="quote__text">
          <template
            v-for="(segment, segmentIndex) in segments"
            :key="segmentIndex"
          ><mark v-if="segment.hit">{{ segment.text }}</mark><template
              v-else
              >{{ segment.text }}</template
            ></template>
        </p>
      </blockquote>

      <footer class="result__foot">
        <dl class="scores">
          <div class="score">
            <dt>RRF</dt>
            <dd class="mono">{{ formatScore(item.score) }}</dd>
          </div>
          <div class="score">
            <dt>重排</dt>
            <dd class="mono">{{ formatScore(item.rerankScore) }}</dd>
          </div>
        </dl>
        <div class="copy">
          <button
            class="button--text copy__button"
            type="button"
            :aria-label="`复制引用：${item.title}`"
            @click="onCopy"
          >
            <span v-if="copyState === 'copied'" class="copy__state"
              >已复制</span
            >
            <span v-else-if="copyState === 'failed'" class="copy__state"
              >复制失败</span
            >
            <span v-else>复制引用</span>
          </button>
          <span class="sr-only" aria-live="polite">{{ copyAnnouncement }}</span>
        </div>
      </footer>

      <p v-if="copyState === 'failed'" class="copy-fallback mono">
        {{ item.citation }}
      </p>
    </div>
  </article>
</template>

<style scoped>
.result {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 0 14px;
  padding-block: 28px;
  animation: result-in 360ms var(--ease-out) both;
}

.result + .result {
  border-top: 1px solid var(--rule);
}

.result__rail {
  padding-top: 4px;
}

.result__rank {
  display: block;
  color: var(--steel);
  font-size: 0.75rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  line-height: 1;
}

.result__main {
  min-width: 0;
}

.result__head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 3px 10px;
  max-width: 72ch;
  font-size: 1.125rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.35;
}

.result__title {
  color: var(--ink);
}

.result__version {
  color: var(--steel);
  font-size: 0.75rem;
  font-weight: 400;
  letter-spacing: 0;
}

.result__sep {
  color: var(--rule);
  font-weight: 400;
}

.result__clause {
  color: var(--steel);
  font-family: var(--font-mono);
  font-size: 0.8125rem;
  font-weight: 400;
  letter-spacing: 0;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.result__page {
  color: var(--ink);
  font-size: 0.75rem;
  font-weight: 500;
  letter-spacing: 0;
}

.result__doc {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 7px;
  color: var(--steel);
  font-size: 0.6875rem;
  letter-spacing: 0.01em;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.result__status {
  color: var(--ink);
  border-bottom: 1px solid var(--rule);
}

.quote {
  max-width: var(--measure);
  margin-top: 14px;
  border-left: 2px solid var(--ink);
  background: var(--surface);
  padding: 14px 18px 15px;
}

.quote__text {
  color: var(--ink);
  font-family: var(--font-serif);
  font-size: 1rem;
  font-weight: 400;
  line-height: var(--line-quote);
  letter-spacing: 0.005em;
  white-space: pre-line;
  overflow-wrap: anywhere;
}

.result__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px 24px;
  margin-top: 14px;
}

.scores {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 26px;
}

.score {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.score dt {
  color: var(--steel);
  font-size: 0.6875rem;
  letter-spacing: 0.02em;
}

.score dd {
  color: var(--ink);
  font-size: 0.75rem;
  font-weight: 500;
  letter-spacing: 0.01em;
}

.copy {
  margin-left: auto;
}

.copy__button {
  text-decoration: none;
  border-left: 1px solid var(--rule);
  padding-left: 12px;
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  letter-spacing: 0.01em;
}

.copy__button:hover {
  color: var(--ink);
}

.copy__state {
  color: var(--verified);
}

.copy-fallback {
  max-width: 72ch;
  margin-top: 10px;
  border-left: 2px solid var(--rule);
  padding: 8px 12px;
  background: var(--surface);
  color: var(--ink);
  font-size: 0.75rem;
  line-height: 1.6;
  overflow-wrap: anywhere;
  user-select: all;
}

@keyframes result-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@media (max-width: 600px) {
  .result {
    grid-template-columns: 26px minmax(0, 1fr);
    gap: 0 8px;
    padding-block: 22px;
  }

  .result__head {
    font-size: 1.0625rem;
  }

  .result__sep {
    display: none;
  }

  .result__clause {
    font-size: 0.75rem;
  }

  .quote {
    padding: 12px 14px 13px;
  }

  .quote__text {
    font-size: 0.9375rem;
    line-height: 1.72;
  }

  .copy {
    margin-left: 0;
  }

  .copy__button {
    padding-left: 0;
    border-left: 0;
  }
}
</style>
