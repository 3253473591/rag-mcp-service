<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { pad2, todayIso } from '../lib/format'
import type { SearchFormState } from '../lib/searchForm'

const props = defineProps<{ form: SearchFormState; loading: boolean }>()
const emit = defineEmits<{ submit: [] }>()

const textarea = ref<HTMLTextAreaElement | null>(null)
const dateInput = ref<HTMLInputElement | null>(null)
const emptyAttempt = ref(false)

const strategies = [
  { value: 'hybrid', label: 'hybrid' },
  { value: 'vector', label: 'vector' },
  { value: 'keyword', label: 'keyword' },
] as const

const chunkStrategies = [
  { value: 'heading', label: 'heading' },
  { value: 'fixed', label: 'fixed' },
] as const

watch(
  () => props.form.query,
  (value) => {
    if (value.trim()) emptyAttempt.value = false
  },
)

function submit(): void {
  if (props.form.query.trim().length === 0) {
    emptyAttempt.value = true
    textarea.value?.focus()
    return
  }
  if (props.form.query.length > 500) return
  emptyAttempt.value = false
  emit('submit')
}

function onEnter(event: KeyboardEvent): void {
  if (event.ctrlKey || event.metaKey) {
    event.preventDefault()
    submit()
  }
}

function setToday(): void {
  props.form.asOf = todayIso()
  nextTick(() => dateInput.value?.focus())
}
</script>

<template>
  <form class="search-form" novalidate @submit.prevent="submit">
    <h2 class="sr-only">检索条件</h2>

    <div class="field">
      <div class="field__label">
        <label for="query">问题</label>
        <span class="field__hint">{{ form.query.length }} / 500</span>
      </div>
      <textarea
        id="query"
        ref="textarea"
        v-model="form.query"
        class="textarea"
        rows="3"
        maxlength="500"
        placeholder="例如：诊室血压的诊断标准是多少？"
        :aria-invalid="emptyAttempt"
        aria-describedby="query-hint"
        @keydown="onEnter"
      />
      <p id="query-hint" class="form-hint">
        <span v-if="emptyAttempt" class="form-hint__error"
          >先输入问题。空问题不会发送请求。</span
        >
        <span v-else>Ctrl / ⌘ + Enter 提交。越具体的问题越容易命中。</span>
      </p>
    </div>

    <div class="form-rule field">
      <div class="field__label">
        <label for="topk">条数</label>
        <output class="range__value mono" for="topk">{{ pad2(form.topK) }}</output>
      </div>
      <input
        id="topk"
        v-model.number="form.topK"
        class="range"
        type="range"
        min="1"
        max="20"
        step="1"
        :aria-valuetext="`${form.topK} 条`"
        aria-describedby="topk-scale"
      />
      <div id="topk-scale" class="range__scale mono" aria-hidden="true">
        <span>1</span>
        <span>20</span>
      </div>
    </div>

    <div class="form-pair">
      <div class="field">
        <label class="field__label" for="strategy">策略</label>
        <select id="strategy" v-model="form.strategy" class="select">
          <option
            v-for="option in strategies"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </div>

      <div class="field">
        <label class="field__label" for="chunk-strategy">切分</label>
        <select id="chunk-strategy" v-model="form.chunkStrategy" class="select">
          <option
            v-for="option in chunkStrategies"
            :key="option.value"
            :value="option.value"
          >
            {{ option.label }}
          </option>
        </select>
      </div>
    </div>

    <p class="form-hint form-hint--pair">
      hybrid 同时走向量与关键词；heading 按标题分节。vector / keyword
      只走单路，fixed 使用固定窗口。
    </p>

    <div class="field">
      <div class="field__label">
        <label for="as-of">生效日</label>
        <button type="button" class="button--text form-today" @click="setToday">
          设为今天
        </button>
      </div>
      <input
        id="as-of"
        ref="dateInput"
        v-model="form.asOf"
        class="input mono"
        type="date"
        min="1900-01-01"
        max="2100-12-31"
      />
      <p class="form-hint">按该日期判断文档版本是否生效。</p>
    </div>

    <label class="check">
      <input v-model="form.includeSuperseded" type="checkbox" />
      <span>
        含被替代版本
        <span class="check__hint">会返回旧版文档，便于追溯历史依据。</span>
      </span>
    </label>

    <div class="form-actions">
      <button class="button search-submit" type="submit" :disabled="loading">
        <span v-if="loading">检索中…</span>
        <span v-else>检索</span>
      </button>
      <p class="form-actions__note">
        只读检索，不写入知识库。按服务端拒答规则判断证据是否足够。
      </p>
    </div>
  </form>
</template>

<style scoped>
.search-form {
  display: grid;
  gap: 20px;
}

.form-rule {
  border-top: 1px solid var(--rule);
  padding-top: 18px;
}

.form-pair {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}

.range {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 22px;
  margin: 0;
  background: transparent;
  cursor: pointer;
}

.range::-webkit-slider-runnable-track {
  height: 2px;
  background: var(--steel);
  opacity: 0.55;
}

.range::-moz-range-track {
  height: 2px;
  background: var(--steel);
  opacity: 0.55;
}

.range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 14px;
  height: 14px;
  margin-top: -6.5px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--ink);
}

.range::-moz-range-thumb {
  width: 12px;
  height: 12px;
  border: 1px solid var(--ink);
  border-radius: 0;
  background: var(--ink);
}

.range:focus-visible {
  outline: none;
}

.range:focus-visible::-webkit-slider-thumb {
  outline: 2px solid var(--ink);
  outline-offset: 3px;
}

.range:focus-visible::-moz-range-thumb {
  outline: 2px solid var(--ink);
  outline-offset: 3px;
}

.range__value {
  color: var(--ink);
  font-size: 1.125rem;
  font-weight: 500;
  line-height: 1;
}

.range__scale {
  display: flex;
  justify-content: space-between;
  margin-top: -4px;
  color: var(--steel);
  font-size: 0.625rem;
}

.form-hint {
  color: var(--steel);
  font-size: 0.75rem;
  line-height: 1.5;
}

.form-hint--pair {
  margin-top: -8px;
  font-size: 0.6875rem;
}

.input[type='date'] {
  color-scheme: light;
}

.input[type='date']::-webkit-calendar-picker-indicator {
  cursor: pointer;
  opacity: 0.55;
}

.form-today {
  color: var(--steel);
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  text-decoration: none;
}

.form-today:hover {
  color: var(--ink);
}

.form-hint__error {
  color: var(--ink);
  font-weight: 500;
}

.check__hint {
  display: block;
  color: var(--steel);
  font-size: 0.75rem;
  line-height: 1.5;
}

.form-actions {
  display: grid;
  gap: 10px;
  border-top: 1px solid var(--rule);
  padding-top: 18px;
}

.search-submit {
  width: 100%;
  min-height: 44px;
  letter-spacing: 0.08em;
}

.form-actions__note {
  color: var(--steel);
  font-size: 0.6875rem;
  line-height: 1.55;
}

@media (max-width: 768px) {
  .search-form {
    gap: 18px;
  }
}
</style>
