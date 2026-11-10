<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import AbstainPanel from '../components/AbstainPanel.vue'
import ProblemPanel from '../components/ProblemPanel.vue'
import ResultItem from '../components/ResultItem.vue'
import SearchForm from '../components/SearchForm.vue'
import SkeletonResults from '../components/SkeletonResults.vue'
import { formatDate, pad2 } from '../lib/format'
import {
  formFromPayload,
  toSearchPayload,
  type SearchFormState,
} from '../lib/searchForm'
import { searchState, searchStore } from '../stores/search'

const form = reactive<SearchFormState>(formFromPayload(searchState.payload))
const resultsRef = ref<HTMLElement | null>(null)

const response = computed(() => searchState.response)
const loading = computed(() => searchState.status === 'loading')
const error = computed(() => searchState.error)
const results = computed(() => response.value?.results ?? [])
const abstained = computed(() => response.value?.abstained === true)
const showIdle = computed(
  () => searchState.status === 'idle' && response.value === null,
)

const hasRerank = computed(() =>
  results.value.some((item) => item.rerankScore !== null),
)

const sortNote = computed(() => {
  if (results.value.length === 0) return '本次没有返回结果，不涉及排序。'
  return hasRerank.value
    ? '按重排分排序；RRF 是融合名次分，与重排分量纲不同，不能直接比较。'
    : '按 RRF 排序；本次响应不含重排分。'
})

const errorNext = computed(() => {
  const current = error.value
  if (!current) return ''
  if (current.kind === 'transport') {
    return '开发环境请确认后端已在 127.0.0.1:8080 启动，再重试；若后端已启动，检查 Vite 代理配置。'
  }
  switch (current.code) {
    case 'ERR-EMBEDDING_UNAVAILABLE':
    case 'ERR-EMBEDDING_TIMEOUT':
      return '检索依赖 Ollama 的 bge-m3。先确认 Ollama 已启动并已拉取模型，再打开 /actuator/health 查看 embedding 组件状态。'
    case 'ERR-DB_UNAVAILABLE':
      return 'PostgreSQL 暂时不可用。先确认数据库容器健康，再重试；traceId 可用于定位连接失败。'
    case 'ERR-VALIDATION':
    case 'ERR-INVALID_AS_OF':
      return '检查问题长度、条数、策略、切分方式与生效日后重试。'
    case 'ERR-INTERNAL':
      return '服务内部错误。带上 traceId 查看后端日志；如果服务正在重启，稍后重试。'
    default:
      return '确认后端与知识库可用后重试；持续失败时带上 traceId 查看服务端日志。'
  }
})

const examples = [
  { question: '诊室血压的诊断标准是多少？', tag: '命中' },
  { question: '公司年假有多少天？', tag: '未检索到' },
  { question: '患者张三最近的血压记录是多少？', tag: '噪声过多' },
] as const

function scrollToResultsOnNarrowScreen(): void {
  if (!window.matchMedia('(max-width: 900px)').matches) return
  void nextTick(() => {
    resultsRef.value?.scrollIntoView({ block: 'start', behavior: 'auto' })
  })
}

function runSearch(): void {
  const payload = toSearchPayload(form)
  if (!payload.query) return
  void searchStore.run(payload)
  scrollToResultsOnNarrowScreen()
}

function runExample(question: string): void {
  form.query = question
  runSearch()
}

function retry(): void {
  if (!searchState.payload) return
  void searchStore.run(searchState.payload)
}
</script>

<template>
  <div class="shell search-page">
    <h1 class="sr-only">证据检索</h1>

    <div class="search-layout">
      <aside class="search-rail">
        <SearchForm :form="form" :loading="loading" @submit="runSearch" />
      </aside>

      <section
        ref="resultsRef"
        class="results"
        aria-live="polite"
        :aria-busy="loading"
      >
        <header v-if="response || loading" class="results-head">
          <p class="results-head__summary mono">
            <template v-if="response">
              <strong class="results-head__count">{{
                pad2(results.length)
              }}</strong>
              条结果
              <span class="results-head__sep" aria-hidden="true">·</span>
              {{ response.tookMs }}ms
              <span class="results-head__sep" aria-hidden="true">·</span>
              {{ response.strategy }} / {{ response.chunkStrategy }}
            </template>
            <template v-else>检索中…</template>
          </p>
          <div v-if="response" class="results-head__meta">
            <p class="mono">asOf {{ formatDate(response.asOf) }}</p>
            <p class="sort-note">{{ sortNote }}</p>
          </div>
        </header>

        <SkeletonResults v-if="loading" />

        <template v-else-if="response">
          <AbstainPanel
            v-if="abstained"
            class="results__abstain"
            :response="response"
          />

          <div v-else-if="results.length" class="results-list">
            <ResultItem
              v-for="(item, index) in results"
              :key="`${searchState.runId}-${item.chunkId}`"
              :item="item"
              :index="index"
              :query="response.query"
            />
          </div>

          <p v-else class="results-empty">
            服务返回了空结果，但没有给出拒答原因。重试一次，或带上 traceId
            查看后端日志。
          </p>
        </template>

        <ProblemPanel
          v-else-if="error"
          title="检索没有完成"
          :code="error.code"
          :message="error.message"
          :trace-id="error.traceId"
          :status="error.status"
          :next="errorNext"
        >
          <template #actions>
            <button class="button" type="button" @click="retry">
              重试检索
            </button>
          </template>
        </ProblemPanel>

        <section v-else-if="showIdle" class="empty" aria-labelledby="empty-title">
          <h2 id="empty-title" class="empty__title">
            先给出一个可核对的问题
          </h2>
          <p class="empty__lead">
            检索会返回原始片段，并标明文档、版本、条款和页码。证据不足时，服务会拒答并列出判定读数，不会编造答案。
          </p>

          <div class="empty__examples">
            <p class="empty__label">示例问题（点击即检索）</p>
            <ul>
              <li v-for="example in examples" :key="example.question">
                <button
                  class="example"
                  type="button"
                  @click="runExample(example.question)"
                >
                  <span class="example__question">{{ example.question }}</span>
                  <span class="example__tag mono">{{ example.tag }}</span>
                </button>
              </li>
            </ul>
          </div>

          <p class="empty__note">
            示例来自仓库的验收查询：一次命中、一次未检索到、一次噪声过多。空问题不会发送请求。
          </p>
        </section>
      </section>
    </div>
  </div>
</template>

<style scoped>
.search-page {
  padding-top: 40px;
}

.search-layout {
  display: grid;
  grid-template-columns: var(--rail) minmax(0, 1fr);
  gap: 56px;
  align-items: start;
}

.search-rail {
  position: sticky;
  top: 96px;
}

.results {
  min-width: 0;
  scroll-margin-top: 88px;
}

.results-head {
  border-bottom: 1px solid var(--rule);
  padding-bottom: 13px;
}

.results-head__summary {
  color: var(--steel);
  font-size: 0.8125rem;
  letter-spacing: 0.015em;
  line-height: 1.5;
}

.results-head__count {
  color: var(--ink);
  font-size: 1rem;
  font-weight: 500;
}

.results-head__sep {
  padding-inline: 2px;
  color: var(--rule);
}

.results-head__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 24px;
  margin-top: 7px;
  color: var(--steel);
  font-size: 0.6875rem;
  line-height: 1.5;
}

.sort-note {
  max-width: 72ch;
}

.results-list {
  border-bottom: 1px solid var(--rule);
}

.results__abstain {
  margin-top: 26px;
}

.results-empty {
  max-width: 62ch;
  padding-block: 28px;
  border-bottom: 1px solid var(--rule);
  color: var(--steel);
  font-size: 0.875rem;
}

.empty {
  max-width: 68ch;
  padding-top: 6px;
}

.empty__title {
  color: var(--ink);
  font-size: 1.5rem;
  font-weight: 700;
  letter-spacing: -0.025em;
}

.empty__lead {
  max-width: 62ch;
  margin-top: 10px;
  color: var(--steel);
  font-size: 0.9375rem;
  line-height: 1.7;
}

.empty__examples {
  margin-top: 34px;
  border-top: 1px solid var(--rule);
}

.empty__label {
  border-bottom: 1px solid var(--rule);
  padding-block: 10px;
  color: var(--steel);
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  letter-spacing: 0.02em;
}

.example {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  width: 100%;
  border-bottom: 1px solid var(--rule);
  padding: 14px 0;
  color: var(--ink);
  font-size: 0.9375rem;
  line-height: 1.5;
  text-align: left;
}

.example:hover .example__question {
  color: var(--verified);
}

.example__tag {
  flex: none;
  color: var(--steel);
  font-size: 0.625rem;
  letter-spacing: 0.03em;
}

.empty__note {
  max-width: 62ch;
  margin-top: 14px;
  color: var(--steel);
  font-size: 0.75rem;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .search-layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }

  .search-rail {
    position: static;
  }
}

@media (max-width: 600px) {
  .search-page {
    padding-top: 24px;
  }

  .empty__title {
    font-size: 1.3125rem;
  }

  .example {
    gap: 14px;
    font-size: 0.875rem;
  }

  .results-head__meta {
    display: block;
  }

  .sort-note {
    margin-top: 3px;
  }
}
</style>
