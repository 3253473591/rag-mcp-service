<script setup lang="ts">
import { computed } from 'vue'
import type { AbstainDiagnostics } from '../api/types'
import { formatScore } from '../lib/format'

const props = defineProps<{ diagnostics: AbstainDiagnostics }>()

const DOMAIN_MIN = 0.3
const DOMAIN_MAX = 0.9
const MAX_DOTS = 40

function pct(value: number): number {
  const clamped = Math.min(DOMAIN_MAX, Math.max(DOMAIN_MIN, value))
  return ((clamped - DOMAIN_MIN) / (DOMAIN_MAX - DOMAIN_MIN)) * 100
}

const dots = computed(() =>
  props.diagnostics.candidateScores
    .slice(0, MAX_DOTS)
    .map((value, index) => ({ value, index, left: pct(value) })),
)

const topLeft = computed(() => pct(props.diagnostics.topScore))
const topLabelLeft = computed(() => Math.min(94, Math.max(6, topLeft.value)))
const minLeft = computed(() => pct(props.diagnostics.minTopScore))
const highLeft = computed(() => pct(props.diagnostics.highConfidenceTopScore))

const caption = computed(() => {
  const shown = Math.min(
    props.diagnostics.candidateScores.length,
    MAX_DOTS,
  )
  const total = props.diagnostics.candidateCount
  if (shown === 0) return '候选余弦分布：没有可展示的候选分数'
  return `候选余弦分布：共 ${total} 个候选，图中展示前 ${shown} 个`
})

const accessibleSummary = computed(
  () =>
    `候选余弦分布。最高分 ${formatScore(props.diagnostics.topScore)}，` +
    `绝对下限 ${formatScore(props.diagnostics.minTopScore)}，` +
    `高置信阈值 ${formatScore(props.diagnostics.highConfidenceTopScore)}。`,
)
</script>

<template>
  <figure class="ruler">
    <div class="ruler__plot" aria-hidden="true">
      <div class="ruler__line"></div>

      <span
        class="ruler__threshold"
        :style="{ left: `${minLeft}%` }"
      ></span>
      <span
        class="ruler__threshold"
        :style="{ left: `${highLeft}%` }"
      ></span>

      <span
        v-for="dot in dots"
        :key="dot.index"
        class="ruler__dot"
        :style="{ left: `${dot.left}%` }"
      ></span>

      <span class="ruler__top" :style="{ left: `${topLeft}%` }"></span>
      <span
        class="ruler__top-label mono"
        :style="{ left: `${topLabelLeft}%` }"
        >{{ formatScore(diagnostics.topScore) }}</span
      >
    </div>

    <div class="ruler__axis mono" aria-hidden="true">
      <span class="ruler__axis-edge">0.30</span>
      <span class="ruler__axis-tick" :style="{ left: `${minLeft}%` }"
        >{{ formatScore(diagnostics.minTopScore) }}</span
      >
      <span class="ruler__axis-tick" :style="{ left: `${highLeft}%` }"
        >{{ formatScore(diagnostics.highConfidenceTopScore) }}</span
      >
      <span class="ruler__axis-edge ruler__axis-edge--end">0.90</span>
    </div>

    <figcaption class="ruler__caption">
      <span>{{ caption }}</span>
      <span class="ruler__legend">虚线为下限与高置信阈值，粗线为最高分。</span>
    </figcaption>
    <p class="sr-only">{{ accessibleSummary }}</p>
  </figure>
</template>

<style scoped>
.ruler {
  margin: 0;
}

.ruler__plot {
  position: relative;
  height: 46px;
  margin: 18px 12px 0;
}

.ruler__line {
  position: absolute;
  left: 0;
  right: 0;
  top: 22px;
  height: 1px;
  background: var(--rule);
}

.ruler__threshold {
  position: absolute;
  top: 8px;
  height: 29px;
  border-left: 1px dotted var(--steel);
  opacity: 0.75;
}

.ruler__dot {
  position: absolute;
  top: 20px;
  width: 3px;
  height: 3px;
  background: var(--steel);
  opacity: 0.55;
}

.ruler__top {
  position: absolute;
  top: 8px;
  width: 2px;
  height: 29px;
  background: var(--ink);
}

.ruler__top-label {
  position: absolute;
  top: -1px;
  transform: translateX(-50%);
  color: var(--ink);
  font-size: 0.625rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.ruler__axis {
  position: relative;
  height: 18px;
  margin: 0 12px;
  color: var(--steel);
  font-size: 0.5625rem;
  letter-spacing: 0.02em;
}

.ruler__axis-edge {
  position: absolute;
  left: 0;
}

.ruler__axis-edge--end {
  left: auto;
  right: 0;
}

.ruler__axis-tick {
  position: absolute;
  transform: translateX(-50%);
  white-space: nowrap;
}

.ruler__caption {
  margin-top: 12px;
  color: var(--steel);
  font-size: 0.6875rem;
  line-height: 1.5;
}

.ruler__legend {
  display: block;
  margin-top: 3px;
  color: var(--steel);
}

@media (max-width: 600px) {
  .ruler__plot {
    margin-inline: 8px;
  }

  .ruler__axis {
    margin-inline: 8px;
  }

}
</style>
