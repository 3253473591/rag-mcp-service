<script setup lang="ts">
import { computed } from 'vue'
import type { SearchResponse } from '../api/types'
import { formatScore, reasonLabel } from '../lib/format'
import ScoreRuler from './ScoreRuler.vue'

const props = defineProps<{ response: SearchResponse }>()

const diagnostics = computed(() => props.response.diagnostics)
const reason = computed(() => props.response.abstainReason)
const message = computed(
  () => props.response.message?.trim() || '服务没有返回拒答说明。',
)

const reasonTitle = computed(() => reasonLabel(reason.value))

const explanation = computed(() => {
  if (diagnostics.value && !diagnostics.value.enabled) {
    return '分数守卫当前关闭。这次没有结果是服务端对空结果的兜底说明，不能按余弦阈值解释。'
  }
  if (reason.value === 'NO_MATCH') {
    return '最高余弦相似度低于绝对下限，知识库里没有足够接近的内容。系统选择不给结果，避免把不相关片段当成依据。'
  }
  if (reason.value === 'TOO_NOISY') {
    return '最高分已经越过下限，但灰色区间里的候选过多，且前两名分差小于最小分差。系统无法判断哪一条才是证据，因此整次拒答。'
  }
  return '检索没有形成可用的证据集合。判定读数保留了当时的阈值与候选分布，便于复核。'
})

const nextStep = computed(() => {
  if (reason.value === 'NO_MATCH') {
    return '换用更具体的文档名、条款号或指标名重试；如果知识库确实缺少这份资料，先到文档库上传对应文件。'
  }
  if (reason.value === 'TOO_NOISY') {
    return '把问题收窄到具体条款、指标或文档范围后重试；过于笼统的问题更容易落在灰色区间。'
  }
  return '补充更明确的问题范围后重试，或带上 traceId 查看后端日志。'
})

const rows = computed(() => {
  const value = diagnostics.value
  if (!value) return []
  return [
    {
      key: 'topScore',
      label: '最高余弦',
      value: formatScore(value.topScore),
    },
    {
      key: 'candidateCount',
      label: '候选数',
      value: String(value.candidateCount),
    },
    {
      key: 'denseCount',
      label: '达标候选',
      value: String(value.denseCount),
    },
    {
      key: 'minTopScore',
      label: '绝对下限',
      value: formatScore(value.minTopScore),
    },
    {
      key: 'highConfidenceTopScore',
      label: '高置信阈值',
      value: formatScore(value.highConfidenceTopScore),
    },
    {
      key: 'enabled',
      label: '守卫开关',
      value: value.enabled ? '开' : '关',
    },
  ]
})
</script>

<template>
  <section class="abstain" aria-labelledby="abstain-title">
    <div class="abstain__head">
      <span class="abstain__stamp mono">拒答</span>
      <h2 id="abstain-title" class="abstain__title">{{ reasonTitle }}</h2>
    </div>

    <p class="abstain__message">{{ message }}</p>
    <p class="abstain__explain">{{ explanation }}</p>

    <div v-if="diagnostics" class="abstain__diag">
      <div>
        <h3 class="abstain__diag-title">判定读数</h3>
        <dl class="diag">
          <div v-for="row in rows" :key="row.key" class="diag__row">
            <dt>
              <span class="diag__key mono">{{ row.key }}</span>
              <span class="diag__label">{{ row.label }}</span>
            </dt>
            <dd class="mono">{{ row.value }}</dd>
          </div>
        </dl>
      </div>
      <ScoreRuler :diagnostics="diagnostics" />
    </div>
    <p v-else class="abstain__explain">
      服务没有返回判定读数，无法复核阈值。
    </p>

    <p class="abstain__next">下一步：{{ nextStep }}</p>
  </section>
</template>

<style scoped>
.abstain {
  border-top: 2px solid var(--stamp);
  border-bottom: 1px solid var(--stamp);
  padding-block: 24px 22px;
  animation: abstain-open 420ms var(--ease-out) both;
}

.abstain__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
}

.abstain__stamp {
  display: inline-block;
  border: 1px solid var(--stamp);
  padding: 3px 9px;
  color: var(--stamp);
  font-size: 0.6875rem;
  font-weight: 500;
  letter-spacing: 0.18em;
  line-height: 1.2;
}

.abstain__title {
  color: var(--stamp);
  font-size: 1.5rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  line-height: 1.2;
}

.abstain__message {
  max-width: 60ch;
  margin-top: 16px;
  color: var(--ink);
  font-size: 1.0625rem;
  line-height: 1.7;
}

.abstain__explain {
  max-width: 62ch;
  margin-top: 10px;
  color: var(--steel);
  font-size: 0.875rem;
  line-height: 1.65;
}

.abstain__diag {
  display: grid;
  grid-template-columns: minmax(0, 330px) minmax(0, 1fr);
  gap: 36px;
  align-items: start;
  margin-top: 26px;
}

.abstain__diag-title {
  margin-bottom: 8px;
  color: var(--steel);
  font-family: var(--font-body);
  font-size: 0.75rem;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.diag {
  border-top: 1px solid var(--rule);
}

.diag__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid var(--rule);
  padding: 7px 0;
}

.diag dt {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.diag__key {
  color: var(--steel);
  font-size: 0.6875rem;
  letter-spacing: 0.01em;
}

.diag__label {
  color: var(--steel);
  font-size: 0.75rem;
}

.diag dd {
  color: var(--ink);
  font-size: 0.8125rem;
  font-weight: 500;
  text-align: right;
}

.abstain__next {
  max-width: 66ch;
  margin-top: 24px;
  border-top: 1px solid var(--rule);
  padding-top: 12px;
  color: var(--ink);
  font-size: 0.8125rem;
  line-height: 1.6;
}

@keyframes abstain-open {
  from {
    opacity: 0;
    clip-path: inset(0 0 100% 0);
  }
  to {
    opacity: 1;
    clip-path: inset(0);
  }
}

@media (max-width: 900px) {
  .abstain__diag {
    grid-template-columns: 1fr;
    gap: 24px;
  }
}

@media (max-width: 600px) {
  .abstain {
    padding-block: 20px 18px;
  }

  .abstain__title {
    font-size: 1.25rem;
  }

  .abstain__message {
    font-size: 1rem;
  }

  .diag__key {
    font-size: 0.625rem;
  }

  .diag__label {
    font-size: 0.6875rem;
  }
}
</style>
