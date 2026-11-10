<template>
  <div class="skeleton" aria-hidden="true">
    <div v-for="index in 3" :key="index" class="skeleton__result">
      <div class="skeleton__rank sk"></div>
      <div class="skeleton__main">
        <div class="skeleton__head">
          <span class="sk skeleton__title"></span>
          <span class="sk skeleton__version"></span>
          <span class="sk skeleton__clause"></span>
        </div>
        <span class="sk skeleton__doc"></span>
        <div class="skeleton__quote">
          <span class="sk skeleton__line"></span>
          <span class="sk skeleton__line skeleton__line--short"></span>
          <span class="sk skeleton__line skeleton__line--medium"></span>
        </div>
        <div class="skeleton__foot">
          <span class="sk skeleton__score"></span>
          <span class="sk skeleton__score"></span>
          <span class="sk skeleton__action"></span>
        </div>
      </div>
    </div>
  </div>
  <p class="sr-only" role="status">正在检索证据</p>
</template>

<style scoped>
.skeleton__result {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 0 14px;
  padding-block: 28px;
}

.skeleton__result + .skeleton__result {
  border-top: 1px solid var(--rule);
}

.sk {
  display: block;
  height: 10px;
  background: var(--rule);
  opacity: 0.55;
}

.skeleton__rank {
  width: 18px;
  height: 8px;
  margin-top: 6px;
}

.skeleton__main {
  min-width: 0;
}

.skeleton__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.skeleton__title {
  width: min(320px, 52%);
  height: 16px;
}

.skeleton__version {
  width: 40px;
  height: 9px;
}

.skeleton__clause {
  width: min(260px, 36%);
  height: 9px;
}

.skeleton__doc {
  width: min(300px, 46%);
  height: 8px;
  margin-top: 10px;
  opacity: 0.4;
}

.skeleton__quote {
  display: grid;
  gap: 9px;
  max-width: 72ch;
  margin-top: 14px;
  border-left: 2px solid var(--rule);
  background: var(--surface);
  padding: 15px 18px;
}

.skeleton__line {
  width: 96%;
  height: 9px;
  opacity: 0.45;
}

.skeleton__line--short {
  width: 72%;
}

.skeleton__line--medium {
  width: 84%;
}

.skeleton__foot {
  display: flex;
  gap: 26px;
  margin-top: 14px;
}

.skeleton__score {
  width: 84px;
  height: 8px;
}

.skeleton__action {
  width: 54px;
  height: 8px;
  margin-left: auto;
}

@media (max-width: 600px) {
  .skeleton__result {
    grid-template-columns: 26px minmax(0, 1fr);
    gap: 0 8px;
    padding-block: 22px;
  }

  .skeleton__quote {
    padding: 12px 14px;
  }

  .skeleton__action {
    display: none;
  }
}
</style>
