<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { deleteDocument, listDocuments, uploadDocument } from '../api/documents'
import type {
  ChunkStrategy,
  DocumentSummary,
  UploadResponse,
} from '../api/types'
import ProblemPanel from '../components/ProblemPanel.vue'
import { toProblem, type ProblemState } from '../lib/errors'
import {
  formatDate,
  formatDateTime,
  formatRange,
  pad2,
  statusLabel,
  todayIso,
} from '../lib/format'

const PAGE_SIZE = 20
const MAX_FILE_BYTES = 20 * 1024 * 1024
const ACCEPTED_EXTENSIONS = ['pdf', 'docx', 'md', 'markdown', 'txt']

const docs = ref<DocumentSummary[]>([])
const total = ref(0)
const offset = ref(0)
const listLoading = ref(false)
const listError = ref<ProblemState | null>(null)
const deleteNotice = ref('')
let loadToken = 0

const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const fileError = ref('')
const metadata = reactive({
  title: '',
  docKey: '',
  version: '',
  effectiveFrom: todayIso(),
  effectiveTo: '',
})
const strategies = ref<ChunkStrategy[]>(['heading'])
const uploading = ref(false)
const uploadPercent = ref<number | null>(null)
const uploadError = ref<ProblemState | null>(null)
const uploadResult = ref<UploadResponse | null>(null)

const confirmingId = ref<string | null>(null)
const deletingId = ref<string | null>(null)
const deleteError = ref<ProblemState | null>(null)

const pageStart = computed(() => (total.value === 0 ? 0 : offset.value + 1))
const pageEnd = computed(() =>
  total.value === 0 ? 0 : Math.min(offset.value + docs.value.length, total.value),
)
const pageNumber = computed(() => Math.floor(offset.value / PAGE_SIZE) + 1)
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const hasPrev = computed(() => offset.value > 0)
const hasNext = computed(() => offset.value + PAGE_SIZE < total.value)

const canUpload = computed(
  () =>
    selectedFile.value !== null &&
    strategies.value.length > 0 &&
    !uploading.value,
)

const sizeLabel = computed(() => {
  const file = selectedFile.value
  if (!file) return ''
  if (file.size < 1024 * 1024) return `${Math.max(1, Math.round(file.size / 1024))} KB`
  return `${(file.size / (1024 * 1024)).toFixed(1)} MB`
})

const strategyError = computed(() =>
  strategies.value.length === 0 ? '至少保留一种切分方式，否则无法生成片段。' : '',
)

const uploadProgressLabel = computed(() =>
  uploadPercent.value === null
    ? '上传中…'
    : `上传中 ${uploadPercent.value}%`,
)

const uploadResultMessage = computed(() => {
  const result = uploadResult.value
  if (!result) return ''
  if (result.duplicate) {
    return `内容已存在：返回已有文档《${result.title}》，未重新解析或切分。`
  }
  const counts = Object.entries(result.chunkCounts ?? {})
    .filter(([, count]) => count > 0)
    .map(([strategy, count]) => `${strategy} ${count}`)
    .join(' · ')
  return `已上传《${result.title}》：生成 ${result.chunkCount} 个片段${
    counts ? `（${counts}）` : ''
  }，用时 ${result.tookMs}ms。`
})

const uploadErrorNext = computed(() => {
  const current = uploadError.value
  if (!current) return ''
  switch (current.code) {
    case 'ERR-FILE_TOO_LARGE':
      return '压缩或拆分文件后重试；单文件上限是 20MB。'
    case 'ERR-UNSUPPORTED_TYPE':
      return '改用 PDF、DOCX、Markdown 或 TXT 后重试。'
    case 'ERR-PARSE_FAILED':
      return '文件可能没有可提取文本（例如扫描件）。换一份可复制文本的文件后重试。'
    case 'ERR-EMBEDDING_UNAVAILABLE':
    case 'ERR-EMBEDDING_TIMEOUT':
      return '文件解析成功但向量化失败。确认 Ollama 已启动并已拉取 bge-m3 后重试；重复上传按内容校验，不会生成第二份。'
    case 'ERR-DB_UNAVAILABLE':
      return 'PostgreSQL 暂时不可用。确认数据库容器健康后重试。'
    case 'ERR-VALIDATION':
      return '检查 docKey 与版本是否已被占用、生效区间是否重叠，以及各字段长度。'
    default:
      return '确认后端与 Ollama 可用后重试；持续失败时带上 traceId 查看服务端日志。'
  }
})

const listErrorNext = computed(() => {
  const current = listError.value
  if (!current) return ''
  if (current.kind === 'transport') {
    return '确认后端已在 127.0.0.1:8080 启动；开发时还要确认 Vite 代理可用。'
  }
  if (current.code === 'ERR-DB_UNAVAILABLE') {
    return '数据库暂时不可用。确认 PostgreSQL 容器健康后重试。'
  }
  return '带 traceId 查看后端日志，然后重试。'
})

async function loadDocuments(targetOffset = offset.value): Promise<void> {
  const token = ++loadToken
  listLoading.value = true
  listError.value = null
  try {
    const response = await listDocuments(PAGE_SIZE, Math.max(0, targetOffset))
    if (token !== loadToken) return
    docs.value = response.items
    total.value = response.total
    offset.value = response.offset
  } catch (error) {
    if (token !== loadToken) return
    listError.value = toProblem(error, '读取文档列表失败')
  } finally {
    if (token === loadToken) listLoading.value = false
  }
}

function refresh(): void {
  deleteNotice.value = ''
  void loadDocuments(offset.value)
}

function goPage(direction: number): void {
  if (listLoading.value) return
  const target = offset.value + direction * PAGE_SIZE
  if (target < 0) return
  void loadDocuments(target)
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const picked = input.files?.[0] ?? null
  fileError.value = ''
  selectedFile.value = null
  if (!picked) return

  const extension = picked.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ACCEPTED_EXTENSIONS.includes(extension)) {
    fileError.value = `不支持 .${extension || '未知'} 文件；请选择 PDF、DOCX、MD 或 TXT。`
    input.value = ''
    return
  }
  if (picked.size > MAX_FILE_BYTES) {
    fileError.value = `文件是 ${(picked.size / (1024 * 1024)).toFixed(1)}MB，超过 20MB 上限。`
    input.value = ''
    return
  }
  selectedFile.value = picked
}

function clearFile(): void {
  selectedFile.value = null
  fileError.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

function resetUploadFields(): void {
  clearFile()
  metadata.title = ''
  metadata.docKey = ''
  metadata.version = ''
  metadata.effectiveFrom = todayIso()
  metadata.effectiveTo = ''
  strategies.value = ['heading']
}

async function submitUpload(): Promise<void> {
  if (!selectedFile.value) {
    fileError.value = '先选择要上传的文件。'
    return
  }
  if (strategies.value.length === 0) {
    fileError.value = '至少保留一种切分方式。'
    return
  }

  uploading.value = true
  uploadPercent.value = 0
  uploadError.value = null
  uploadResult.value = null
  fileError.value = ''

  try {
    const result = await uploadDocument(
      {
        file: selectedFile.value,
        title: metadata.title,
        docKey: metadata.docKey,
        version: metadata.version,
        effectiveFrom: metadata.effectiveFrom,
        effectiveTo: metadata.effectiveTo,
        chunkStrategies: [...strategies.value],
      },
      {
        onProgress: (percent) => {
          uploadPercent.value = percent
        },
      },
    )
    uploadResult.value = result
    resetUploadFields()
    await loadDocuments(0)
  } catch (error) {
    uploadError.value = toProblem(error, '上传没有完成')
  } finally {
    uploading.value = false
    uploadPercent.value = null
  }
}

function askDelete(doc: DocumentSummary): void {
  confirmingId.value = doc.docId
  deleteError.value = null
  deleteNotice.value = ''
  void nextTick(() => {
    document
      .querySelector<HTMLButtonElement>('.confirm-actions .button')
      ?.focus()
  })
}

function cancelDelete(): void {
  if (deletingId.value) return
  confirmingId.value = null
  deleteError.value = null
}

async function confirmDelete(doc: DocumentSummary): Promise<void> {
  deletingId.value = doc.docId
  deleteError.value = null
  try {
    await deleteDocument(doc.docId)
    deleteNotice.value = `已删除《${doc.title}》，其片段一并移除。`
    confirmingId.value = null
    const target =
      docs.value.length === 1 && offset.value > 0
        ? offset.value - PAGE_SIZE
        : offset.value
    await loadDocuments(Math.max(0, target))
  } catch (error) {
    deleteError.value = toProblem(error, '删除文档失败')
  } finally {
    deletingId.value = null
  }
}

onMounted(() => {
  void loadDocuments(0)
})
</script>

<template>
  <div class="shell library-page">
    <header class="page-head">
      <div>
        <h1>文档库</h1>
        <p class="page-head__lead">
          上传资料、核对版本与切分块。删除文档会同时移除其片段，不能撤销。
        </p>
      </div>
      <p class="page-head__count mono">{{ pad2(total) }} 篇</p>
    </header>

    <section class="upload-section" aria-labelledby="upload-title">
      <div class="section-head">
        <h2 id="upload-title">上传文档</h2>
        <p>
          同一内容按 SHA-256 幂等：重复上传返回已有文档，不重新解析或切分。
        </p>
      </div>

      <form class="upload-form" novalidate @submit.prevent="submitUpload">
        <fieldset class="upload-grid" :disabled="uploading">
          <legend class="sr-only">上传文档字段</legend>
          <div class="upload__file">
            <input
              id="upload-file"
              ref="fileInput"
              class="file-input"
              type="file"
              accept=".pdf,.docx,.md,.markdown,.txt"
              @change="onFileChange"
            />
            <div class="file-row">
              <label class="button button--quiet file-label" for="upload-file"
                >选择文件</label
              >
              <span v-if="selectedFile" class="file-name mono"
                >{{ selectedFile.name }} · {{ sizeLabel }}</span
              >
              <span v-else class="file-name"
                >PDF / DOCX / MD / TXT，单文件 ≤ 20MB</span
              >
              <button
                v-if="selectedFile"
                type="button"
                class="button--text file-remove"
                @click="clearFile"
              >
                移除
              </button>
            </div>
            <p v-if="fileError" class="upload-error" role="alert">
              {{ fileError }}
            </p>
          </div>

          <div class="field upload__title">
            <label class="field__label" for="upload-title-field">
              <span>标题</span><span class="field__hint">可选</span>
            </label>
            <input
              id="upload-title-field"
              v-model="metadata.title"
              class="input"
              type="text"
              maxlength="200"
              placeholder="默认取文件名去扩展名"
            />
          </div>

          <div class="field upload__doc-key">
            <label class="field__label" for="upload-doc-key">
              <span>docKey</span><span class="field__hint">可选</span>
            </label>
            <input
              id="upload-doc-key"
              v-model="metadata.docKey"
              class="input mono"
              type="text"
              maxlength="200"
              placeholder="如 hypertension-guideline"
            />
          </div>

          <div class="field">
            <label class="field__label" for="upload-version">
              <span>版本</span><span class="field__hint">可选</span>
            </label>
            <input
              id="upload-version"
              v-model="metadata.version"
              class="input mono"
              type="text"
              maxlength="50"
              placeholder="如 2025"
            />
          </div>

          <div class="field">
            <label class="field__label" for="upload-from">生效日</label>
            <input
              id="upload-from"
              v-model="metadata.effectiveFrom"
              class="input mono"
              type="date"
              min="1900-01-01"
              max="2100-12-31"
            />
          </div>

          <div class="field">
            <label class="field__label" for="upload-to">
              <span>失效日</span><span class="field__hint">留空 = 长期</span>
            </label>
            <input
              id="upload-to"
              v-model="metadata.effectiveTo"
              class="input mono"
              type="date"
              min="1900-01-01"
              max="2100-12-31"
            />
          </div>

          <div class="field upload__strategies" role="group" aria-labelledby="upload-strategies">
            <p id="upload-strategies" class="field__label">切分方式</p>
            <div class="strategy-checks">
              <label class="check">
                <input v-model="strategies" type="checkbox" value="fixed" />
                <span>fixed</span>
              </label>
              <label class="check">
                <input v-model="strategies" type="checkbox" value="heading" />
                <span>heading</span>
              </label>
            </div>
            <p v-if="strategyError" class="upload-hint">{{ strategyError }}</p>
          </div>

          <div class="upload__actions">
            <button class="button" type="submit" :disabled="!canUpload">
              <span v-if="uploading">{{ uploadProgressLabel }}</span>
              <span v-else>上传并切分</span>
            </button>
            <p class="upload-hint">
              上传会写入文档与片段；单文件 20MB，PDF 最多 500 页。
            </p>
          </div>
        </fieldset>

        <div
          v-if="uploading"
          class="upload-progress"
          aria-live="polite"
          role="progressbar"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuenow="uploadPercent ?? undefined"
          :aria-valuetext="uploadProgressLabel"
        >
          <div class="progress-track">
            <span
              class="progress-fill"
              :style="{ width: `${uploadPercent ?? 0}%` }"
            ></span>
          </div>
          <p class="mono">{{ uploadProgressLabel }}</p>
        </div>

        <div
          v-if="uploadResultMessage"
          class="upload-result"
          role="status"
          aria-live="polite"
        >
          <p class="upload-result__title">上传完成</p>
          <p class="upload-result__message">{{ uploadResultMessage }}</p>
          <dl v-if="uploadResult" class="upload-result__meta mono">
            <div>
              <dt>docId</dt>
              <dd>{{ uploadResult.docId }}</dd>
            </div>
            <div>
              <dt>状态</dt>
              <dd>{{ statusLabel(uploadResult.status) }}</dd>
            </div>
            <div>
              <dt>生效</dt>
              <dd>
                {{ formatRange(uploadResult.effectiveFrom, uploadResult.effectiveTo) }}
              </dd>
            </div>
          </dl>
        </div>

        <ProblemPanel
          v-if="uploadError"
          title="上传没有完成"
          :code="uploadError.code"
          :message="uploadError.message"
          :trace-id="uploadError.traceId"
          :status="uploadError.status"
          :next="uploadErrorNext"
        >
          <template #actions>
            <button
              class="button"
              type="button"
              :disabled="uploading"
              @click="submitUpload"
            >
              重试上传
            </button>
          </template>
        </ProblemPanel>
      </form>
    </section>

    <section class="list-section" aria-labelledby="list-title">
      <div class="list-toolbar">
        <h2 id="list-title">文档列表</h2>
        <div class="list-toolbar__meta">
          <p v-if="total > 0" class="mono">
            第 {{ pageStart }}–{{ pageEnd }} / 共 {{ total }} 篇
          </p>
          <button
            class="button--text"
            type="button"
            :disabled="listLoading"
            @click="refresh"
          >
            刷新
          </button>
        </div>
      </div>

      <p v-if="deleteNotice" class="list-notice" role="status" aria-live="polite">
        {{ deleteNotice }}
      </p>

      <ProblemPanel
        v-if="listError"
        title="文档列表没有读取成功"
        :code="listError.code"
        :message="listError.message"
        :trace-id="listError.traceId"
        :status="listError.status"
        :next="listErrorNext"
      >
        <template #actions>
          <button class="button" type="button" @click="refresh">重试读取</button>
        </template>
      </ProblemPanel>

      <div v-else-if="listLoading" class="list-skeleton">
        <div
          v-for="index in 4"
          :key="index"
          class="list-skeleton__row"
          aria-hidden="true"
        >
          <span class="sk list-skeleton__doc"></span>
          <span class="sk list-skeleton__meta"></span>
          <span class="sk list-skeleton__meta"></span>
          <span class="sk list-skeleton__meta"></span>
        </div>
        <p class="sr-only" role="status">正在读取文档列表</p>
      </div>

      <div v-else-if="docs.length === 0" class="list-empty">
        <p class="list-empty__title">还没有文档</p>
        <p>
          用上方的表单上传第一份资料。上传成功后索引立即生效，可以回到检索页验证命中。
        </p>
      </div>

      <div v-else class="table-wrap">
        <table class="doc-table">
          <caption class="sr-only">
            知识库文档列表，含版本、状态、生效区间、片段数与入库时间
          </caption>
          <thead>
            <tr>
              <th scope="col" class="col-doc">文档</th>
              <th scope="col">版本</th>
              <th scope="col">状态</th>
              <th scope="col">生效区间</th>
              <th scope="col" class="col-num">片段</th>
              <th scope="col">入库时间</th>
              <th scope="col" class="col-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="doc in docs" :key="doc.docId">
              <tr class="doc-row">
                <td class="doc-main" data-label="文档">
                  <span class="doc-title">《{{ doc.title }}》</span>
                  <span class="doc-key mono">{{ doc.docKey }}</span>
                </td>
                <td class="mono" data-label="版本">
                  {{ doc.version ? `v${doc.version}` : '—' }}
                </td>
                <td data-label="状态">
                  <span
                    class="status"
                    :class="{ 'status--active': doc.status === 'ACTIVE' }"
                    >{{ statusLabel(doc.status) }}</span
                  >
                  <span class="doc-type">{{ doc.sourceType }}</span>
                </td>
                <td class="mono range-cell" data-label="生效区间">
                  <span class="range__line">{{
                    formatDate(doc.effectiveFrom)
                  }}</span>
                  <span class="range__line"
                    >至
                    {{
                      doc.effectiveTo ? formatDate(doc.effectiveTo) : '长期'
                    }}</span
                  >
                </td>
                <td class="mono col-num" data-label="片段">
                  {{ doc.chunkCount }}
                </td>
                <td class="mono" data-label="入库时间">
                  {{ formatDateTime(doc.createdAt) }}
                </td>
                <td class="col-actions" data-label="操作">
                  <button
                    v-if="confirmingId !== doc.docId"
                    class="button--text"
                    type="button"
                    :aria-label="`删除《${doc.title}》`"
                    @click="askDelete(doc)"
                  >
                    删除
                  </button>
                  <span v-else class="confirming-label mono">待确认</span>
                </td>
              </tr>
              <tr v-if="confirmingId === doc.docId" class="confirm-row">
                <td colspan="7">
                  <div class="confirm-panel" @keydown.esc="cancelDelete">
                    <p class="confirm-copy">
                      删除《{{ doc.title }}》会同时移除
                      {{ doc.chunkCount }} 个片段，且不可恢复。
                    </p>
                    <div class="confirm-actions">
                      <button
                        class="button"
                        type="button"
                        :disabled="deletingId === doc.docId"
                        @click="confirmDelete(doc)"
                      >
                        {{ deletingId === doc.docId ? '删除中…' : '确认删除' }}
                      </button>
                      <button
                        class="button button--quiet"
                        type="button"
                        :disabled="deletingId === doc.docId"
                        @click="cancelDelete"
                      >
                        取消
                      </button>
                    </div>
                    <div v-if="deleteError" class="delete-error" role="alert">
                      <p>{{ deleteError.message }}</p>
                      <p class="mono">
                        code {{ deleteError.code }} · trace
                        {{ deleteError.traceId }}
                      </p>
                      <p class="delete-error__next">
                        确认后端可用后重试；删除没有发生前，文档仍可检索。
                      </p>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <nav v-if="total > 0" class="pagination" aria-label="文档分页">
        <button
          class="button button--quiet"
          type="button"
          :disabled="!hasPrev || listLoading"
          @click="goPage(-1)"
        >
          上一页
        </button>
        <span class="mono">第 {{ pageNumber }} / {{ pageCount }} 页</span>
        <button
          class="button button--quiet"
          type="button"
          :disabled="!hasNext || listLoading"
          @click="goPage(1)"
        >
          下一页
        </button>
      </nav>
    </section>
  </div>
</template>

<style scoped>
.library-page {
  padding-top: 40px;
}

.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 20px;
  border-bottom: 2px solid var(--ink);
}

.page-head h1 {
  font-size: var(--step-3);
  font-weight: 700;
}

.page-head__lead {
  max-width: 62ch;
  margin-top: 8px;
  color: var(--steel);
  font-size: 0.875rem;
  line-height: 1.65;
}

.page-head__count {
  flex: none;
  color: var(--steel);
  font-size: 0.75rem;
  letter-spacing: 0.02em;
}

.upload-section {
  padding-block: 22px 24px;
  border-bottom: 1px solid var(--rule);
}

.section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 24px;
  margin-bottom: 18px;
}

.section-head h2 {
  font-size: 1.25rem;
  font-weight: 700;
}

.section-head p {
  color: var(--steel);
  font-size: 0.75rem;
  line-height: 1.6;
}

.upload-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.upload__file,
.upload__actions {
  grid-column: 1 / -1;
}

.upload__title,
.upload__doc-key {
  grid-column: span 2;
}

.fieldset,
.upload-grid:disabled {
  opacity: 0.72;
}

.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.file-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  border: 1px solid var(--rule);
  background: var(--surface);
  padding: 10px 12px;
}

.file-label {
  min-height: 32px;
  padding: 6px 12px;
  border-color: var(--steel);
  color: var(--ink);
  cursor: pointer;
}

.file-input:focus-visible + .file-row .file-label {
  outline: 2px solid var(--ink);
  outline-offset: 2px;
}

.file-name {
  color: var(--steel);
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

.file-remove {
  margin-left: auto;
}

.upload-error {
  margin-top: 6px;
  color: var(--ink);
  font-size: 0.75rem;
}

.upload-hint {
  color: var(--steel);
  font-size: 0.6875rem;
  line-height: 1.55;
}

.upload__strategies {
  grid-column: span 1;
}

.strategy-checks {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
}

.upload__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 20px;
  border-top: 1px solid var(--rule);
  padding-top: 16px;
}

.upload-progress {
  display: grid;
  gap: 6px;
  margin-top: 14px;
}

.upload-progress p {
  color: var(--steel);
  font-size: 0.6875rem;
}

.progress-track {
  height: 2px;
  background: var(--rule);
}

.progress-fill {
  display: block;
  height: 100%;
  background: var(--ink);
}

.upload-result {
  margin-top: 18px;
  border-left: 2px solid var(--verified);
  background: var(--surface);
  padding: 14px 16px;
}

.upload-result__title {
  color: var(--verified);
  font-size: 0.8125rem;
  font-weight: 500;
}

.upload-result__message {
  margin-top: 4px;
  font-size: 0.875rem;
  line-height: 1.6;
}

.upload-result__meta {
  display: grid;
  gap: 4px;
  margin-top: 10px;
  color: var(--steel);
  font-size: 0.6875rem;
  overflow-wrap: anywhere;
}

.upload-result__meta div {
  display: flex;
  gap: 12px;
}

.upload-result__meta dt {
  flex: none;
  width: 44px;
}

.list-section {
  padding-top: 24px;
}

.list-toolbar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20px;
  border-bottom: 1px solid var(--rule);
  padding-bottom: 10px;
}

.list-toolbar h2 {
  font-size: 1.25rem;
  font-weight: 700;
}

.list-toolbar__meta {
  display: flex;
  align-items: baseline;
  gap: 20px;
  color: var(--steel);
  font-size: 0.6875rem;
}

.list-notice {
  border-bottom: 1px solid var(--rule);
  padding-block: 9px;
  color: var(--verified);
  font-size: 0.75rem;
}

.table-wrap {
  width: 100%;
}

.doc-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.doc-table th {
  border-bottom: 1px solid var(--rule);
  background: var(--surface);
  padding: 9px 12px;
  color: var(--steel);
  font-size: 0.6875rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  text-align: left;
}

.doc-table td {
  border-bottom: 1px solid var(--rule);
  padding: 14px 12px;
  vertical-align: top;
  font-size: 0.8125rem;
  line-height: 1.5;
}

.doc-row:hover {
  background: var(--surface);
}

.col-doc {
  width: 28%;
}

.col-num {
  width: 7%;
  text-align: right;
}

.col-actions {
  width: 8%;
  text-align: right;
}

.doc-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.doc-title {
  font-family: var(--font-display);
  font-size: 0.9375rem;
  font-weight: 600;
  letter-spacing: -0.015em;
  overflow-wrap: anywhere;
}

.doc-key {
  color: var(--steel);
  font-size: 0.6875rem;
  overflow-wrap: anywhere;
}

.status {
  color: var(--steel);
}

.status--active {
  color: var(--verified);
}

.doc-type {
  display: block;
  margin-top: 2px;
  color: var(--steel);
  font-family: var(--font-mono);
  font-size: 0.625rem;
}

.range-cell {
  font-size: 0.6875rem !important;
  color: var(--steel);
}

.range__line {
  display: block;
  white-space: nowrap;
}

.confirming-label {
  color: var(--steel);
  font-size: 0.6875rem;
}

.confirm-row td {
  padding: 0;
  background: var(--surface);
}

.confirm-panel {
  border-left: 2px solid var(--ink);
  padding: 14px 16px;
}

.confirm-copy {
  max-width: 62ch;
  font-size: 0.875rem;
  line-height: 1.6;
}

.confirm-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 12px;
}

.delete-error {
  max-width: 62ch;
  margin-top: 12px;
  border-top: 1px solid var(--rule);
  padding-top: 10px;
  color: var(--ink);
  font-size: 0.75rem;
  line-height: 1.6;
}

.delete-error .mono {
  color: var(--steel);
  font-size: 0.6875rem;
  overflow-wrap: anywhere;
}

.delete-error__next {
  color: var(--steel);
}

.list-skeleton {
  border-bottom: 1px solid var(--rule);
}

.list-skeleton__row {
  display: grid;
  grid-template-columns: 2fr 0.7fr 1fr 1fr;
  gap: 24px;
  border-bottom: 1px solid var(--rule);
  padding: 18px 12px;
}

.list-skeleton__row + .list-skeleton__row {
  border-top: 0;
}

.sk {
  display: block;
  height: 9px;
  background: var(--rule);
  opacity: 0.55;
}

.list-skeleton__doc {
  width: 72%;
  height: 13px;
}

.list-skeleton__meta {
  width: 56%;
}

.list-empty {
  max-width: 62ch;
  padding-block: 36px;
  border-bottom: 1px solid var(--rule);
}

.list-empty__title {
  font-family: var(--font-display);
  font-size: 1.125rem;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.list-empty p + p {
  margin-top: 6px;
  color: var(--steel);
  font-size: 0.875rem;
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding-top: 16px;
}

.pagination .mono {
  color: var(--steel);
  font-size: 0.6875rem;
}

@media (max-width: 1000px) {
  .upload-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .upload__title,
  .upload__doc-key {
    grid-column: span 1;
  }

  .upload__strategies {
    grid-column: span 2;
  }
}

@media (max-width: 768px) {
  .library-page {
    padding-top: 24px;
  }

  .page-head {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .upload-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .upload__title,
  .upload__doc-key,
  .upload__strategies {
    grid-column: span 1;
  }

  .doc-table thead {
    display: none;
  }

  .doc-table,
  .doc-table tbody,
  .doc-table tr,
  .doc-table td {
    display: block;
    width: 100%;
  }

  .doc-table {
    table-layout: auto;
  }

  .doc-table .doc-row {
    border-bottom: 1px solid var(--rule);
    padding: 15px 0;
  }

  .doc-table .doc-row:hover {
    background: transparent;
  }

  .doc-table td {
    display: flex;
    align-items: baseline;
    gap: 12px;
    width: auto;
    padding: 3px 0;
    border: 0;
    text-align: left;
  }

  .doc-table td::before {
    content: attr(data-label);
    flex: none;
    width: 66px;
    color: var(--steel);
    font-family: var(--font-body);
    font-size: 0.6875rem;
  }

  .doc-table td.doc-main {
    display: block;
    padding-bottom: 7px;
  }

  .doc-table td.doc-main::before {
    display: none;
  }

  .doc-table td.col-actions {
    text-align: left;
  }

  .doc-table td.col-actions::before {
    content: attr(data-label);
  }

  .doc-table td.col-num {
    text-align: left;
  }

  .confirm-row {
    border-bottom: 1px solid var(--rule);
  }

  .confirm-row td {
    display: block;
    padding: 0;
  }

  .confirm-row td::before {
    display: none;
  }

  .confirm-panel {
    margin-bottom: 12px;
  }

  .pagination {
    justify-content: space-between;
  }
}

@media (max-width: 420px) {
  .doc-table td::before {
    width: 58px;
  }

  .upload__actions .button {
    width: 100%;
  }
}
</style>
