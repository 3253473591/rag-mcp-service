<script setup lang="ts">
import SiteHeader from './components/SiteHeader.vue'
</script>

<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <SiteHeader />
  <main id="main" tabindex="-1">
    <RouterView />
  </main>
  <footer class="site-footer">
    <div class="shell site-footer__inner">
      <p>离线字体 · 无 CDN · 无 UI 组件库</p>
      <p class="mono">POST /api/v1/search</p>
    </div>
  </footer>
</template>

<style scoped>
main {
  display: block;
  min-height: calc(100vh - 72px - 68px);
  padding-bottom: 72px;
}

main:focus {
  outline: none;
}

.site-footer {
  border-top: 1px solid var(--rule);
  color: var(--steel);
  font-size: 0.6875rem;
  letter-spacing: 0.02em;
}

.site-footer__inner {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px 20px;
  padding-block: 18px;
}

@media (max-width: 600px) {
  main {
    min-height: calc(100vh - 64px - 92px);
    padding-bottom: 48px;
  }
}
</style>
