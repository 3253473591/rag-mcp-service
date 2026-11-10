<template>
  <div class="shell about-page">
    <article class="record">
      <header class="record__head">
        <div>
          <h1 class="record__title">标定单</h1>
          <p class="record__subject">rag-mcp-service · 检索层</p>
        </div>
        <p class="record__seal mono">已标定</p>

        <dl class="record__meta">
          <div>
            <dt>方法</dt>
            <dd>40 条 QA · heading · hybrid · rerank</dd>
          </div>
          <div>
            <dt>样本</dt>
            <dd>35 条可回答 + 5 条不可回答</dd>
          </div>
          <div>
            <dt>口径</dt>
            <dd>同一数据集、同一 asOf、topK=5</dd>
          </div>
        </dl>
      </header>

      <section class="record-section" aria-labelledby="quality-title">
        <h2 id="quality-title" class="record-section__title">检索质量</h2>
        <div class="record-section__body">
          <p class="record-section__lead">
            35 条可回答题，按条款位置判断是否命中；不可回答题不混入 hit@k 的分母。
          </p>
          <dl class="metrics">
            <div class="metric">
              <dt>hit@1</dt>
              <dd class="mono">0.8286</dd>
            </div>
            <div class="metric">
              <dt>hit@3</dt>
              <dd class="mono">0.9429</dd>
            </div>
            <div class="metric metric--key">
              <dt>hit@5</dt>
              <dd class="mono">
                0.9714
                <span class="metric__fraction mono">34 / 35</span>
              </dd>
            </div>
            <div class="metric">
              <dt>MRR</dt>
              <dd class="mono">0.8819</dd>
            </div>
          </dl>
        </div>
      </section>

      <section class="record-section" aria-labelledby="abstain-title">
        <h2 id="abstain-title" class="record-section__title">拒答</h2>
        <div class="record-section__body">
          <p class="record-section__lead">
            同一批判定阈值下，前后各跑一次全部 QA。可回答题的 hit@k 与 MRR
            没有回归。
          </p>
          <dl class="abstain">
            <div class="abstain__row">
              <dt>误报率</dt>
              <dd>
                <span class="mono before">1.0</span>
                <span class="arrow" aria-hidden="true">→</span>
                <span class="mono after">0.2</span>
                <span class="abstain__note">5 条不可回答题仍有 1 条误报</span>
              </dd>
            </div>
            <div class="abstain__row">
              <dt>无答案正确率</dt>
              <dd>
                <span class="mono before">0.0</span>
                <span class="arrow" aria-hidden="true">→</span>
                <span class="mono after">0.8</span>
                <span class="abstain__note">3 条未检索到 + 1 条噪声过多</span>
              </dd>
            </div>
            <div class="abstain__row">
              <dt>可回答题误拒</dt>
              <dd>
                <span class="mono after">0</span>
                <span class="abstain__note">没有任何可回答题被拒</span>
              </dd>
            </div>
          </dl>
          <p class="record-section__foot">
            剩余误报是 N05（2026 新增药物）。要拒掉它需要把下限抬到约 0.675，会同时误伤 5
            条可回答题；按“优先保召回”的约定保留，并在验收报告里记录。
          </p>
        </div>
      </section>

      <section class="record-section" aria-labelledby="tests-title">
        <h2 id="tests-title" class="record-section__title">测试</h2>
        <div class="record-section__body">
          <p class="tests">
            <strong class="tests__count mono">309</strong>
            <span class="tests__label">个自动化测试</span>
            <span class="tests__detail mono">0 失败 · 1 跳过</span>
          </p>
          <p class="record-section__foot">
            跳过的是真实 Ollama smoke，需要显式设置环境变量才会运行，不是被禁用的失败用例。
          </p>
        </div>
      </section>

      <section class="record-section record-section--note" aria-labelledby="note-title">
        <h2 id="note-title" class="record-section__title">口径说明</h2>
        <div class="record-section__body">
          <p class="note">
            评测集只有 40 条，<strong>1 题 = 2.86pp</strong>。所以
            0.9714 只表示在这个小样本上稳定，不表示生产准确率高。没有做 200+
            题、跨领域、人工双标的评测，这里也就不宣称“高准确率”。
          </p>
        </div>
      </section>

      <section class="record-section" aria-labelledby="evidence-title">
        <h2 id="evidence-title" class="record-section__title">复核路径</h2>
        <div class="record-section__body">
          <ul class="paths">
            <li>
              <span class="paths__file mono">eval/qa.jsonl</span>
              <span class="paths__what">40 条问题与 gold</span>
            </li>
            <li>
              <span class="paths__file mono">scripts/eval.ps1</span>
              <span class="paths__what">hit@k、MRR、误报率与误拒门禁</span>
            </li>
            <li>
              <span class="paths__file mono"
                >docs/acceptance/phase7-abstain.md</span
              >
              <span class="paths__what">拒答阈值、before / after 与已知误报</span>
            </li>
            <li>
              <span class="paths__file mono"
                >docs/acceptance/phase9-rerank.md</span
              >
              <span class="paths__what">重排权重与最终检索指标</span>
            </li>
            <li>
              <span class="paths__file mono"
                >docs/acceptance/phase6c-report.md</span
              >
              <span class="paths__what">heading hit@5 0.9714 的来源</span>
            </li>
          </ul>
          <p class="record-section__foot">
            以上是仓库内相对路径，复制到仓库根目录即可查看。本页不放外链，也不引用
            CDN 资源。
          </p>
        </div>
      </section>

      <footer class="record__foot">
        <p>只列可复核事实，不列营销指标。</p>
        <p class="mono">rag-mcp / evidence console</p>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.about-page {
  padding-top: 48px;
}

.record {
  max-width: 920px;
  margin: 0 auto;
  border-top: 2px solid var(--ink);
}

.record__head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 20px 32px;
  border-bottom: 1px solid var(--ink);
  padding-block: 24px 20px;
}

.record__title {
  font-size: var(--step-4);
  font-weight: 700;
  letter-spacing: -0.035em;
  line-height: 1;
}

.record__subject {
  margin-top: 10px;
  color: var(--steel);
  font-family: var(--font-mono);
  font-size: 0.75rem;
  letter-spacing: 0.02em;
}

.record__seal {
  align-self: start;
  border: 1px solid var(--verified);
  padding: 5px 12px;
  color: var(--verified);
  font-size: 0.6875rem;
  font-weight: 500;
  letter-spacing: 0.18em;
  line-height: 1.2;
}

.record__meta {
  display: grid;
  grid-column: 1 / -1;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px 28px;
  padding-top: 6px;
}

.record__meta dt {
  color: var(--steel);
  font-size: 0.6875rem;
}

.record__meta dd {
  margin-top: 2px;
  color: var(--ink);
  font-size: 0.8125rem;
  line-height: 1.5;
}

.record-section {
  display: grid;
  grid-template-columns: 156px minmax(0, 1fr);
  gap: 32px;
  border-bottom: 1px solid var(--rule);
  padding-block: 28px;
}

.record-section__title {
  color: var(--steel);
  font-family: var(--font-body);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: 0.01em;
  line-height: 1.5;
  padding-top: 2px;
}

.record-section__body {
  min-width: 0;
}

.record-section__lead {
  max-width: 62ch;
  color: var(--steel);
  font-size: 0.8125rem;
  line-height: 1.65;
}

.record-section__foot {
  max-width: 62ch;
  margin-top: 14px;
  color: var(--steel);
  font-size: 0.75rem;
  line-height: 1.6;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.metric {
  border-top: 1px solid var(--rule);
  padding-top: 9px;
}

.metric--key {
  border-top-color: var(--ink);
}

.metric dt {
  color: var(--steel);
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  letter-spacing: 0.02em;
}

.metric dd {
  margin-top: 3px;
  color: var(--ink);
  font-size: 1.5rem;
  font-weight: 500;
  letter-spacing: -0.02em;
  line-height: 1.1;
}

.metric--key dd {
  color: var(--verified);
}

.metric__fraction {
  display: block;
  margin-top: 3px;
  color: var(--steel);
  font-size: 0.625rem;
}

.abstain {
  border-top: 1px solid var(--rule);
  margin-top: 18px;
}

.abstain__row {
  display: grid;
  grid-template-columns: 148px minmax(0, 1fr);
  gap: 18px;
  align-items: baseline;
  border-bottom: 1px solid var(--rule);
  padding-block: 10px;
}

.abstain__row dt {
  color: var(--steel);
  font-size: 0.75rem;
}

.abstain__row dd {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
}

.before {
  color: var(--steel);
  font-size: 0.9375rem;
}

.after {
  color: var(--verified);
  font-size: 1.0625rem;
  font-weight: 500;
}

.arrow {
  color: var(--steel);
  font-size: 0.8125rem;
}

.abstain__note {
  color: var(--steel);
  font-size: 0.6875rem;
}

.tests {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 14px;
  border-top: 1px solid var(--rule);
  padding-top: 14px;
}

.tests__count {
  color: var(--ink);
  font-size: 2.25rem;
  font-weight: 500;
  letter-spacing: -0.04em;
  line-height: 1;
}

.tests__label {
  font-size: 0.875rem;
}

.tests__detail {
  color: var(--steel);
  font-size: 0.6875rem;
}

.note {
  max-width: 62ch;
  border-left: 2px solid var(--ink);
  padding-left: 16px;
  font-size: 0.9375rem;
  line-height: 1.7;
}

.note strong {
  font-weight: 500;
}

.paths {
  border-top: 1px solid var(--rule);
}

.paths li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 3px 20px;
  border-bottom: 1px solid var(--rule);
  padding-block: 8px;
}

.paths__file {
  color: var(--ink);
  font-size: 0.75rem;
  overflow-wrap: anywhere;
}

.paths__what {
  color: var(--steel);
  font-size: 0.6875rem;
}

.record__foot {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 6px 20px;
  padding-block: 20px 8px;
  color: var(--steel);
  font-size: 0.6875rem;
}

@media (max-width: 768px) {
  .about-page {
    padding-top: 28px;
  }

  .record__head {
    grid-template-columns: minmax(0, 1fr);
  }

  .record__seal {
    justify-self: start;
  }

  .record__meta {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }

  .record-section {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
    padding-block: 22px;
  }

  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 14px;
  }

  .abstain__row {
    grid-template-columns: minmax(0, 1fr);
    gap: 4px;
  }
}

@media (max-width: 420px) {
  .record__title {
    font-size: 2rem;
  }

  .tests__count {
    font-size: 1.875rem;
  }
}
</style>
