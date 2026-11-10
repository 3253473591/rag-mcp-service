<script setup lang="ts">
const links = [
  { to: '/', label: '检索' },
  { to: '/library', label: '文档库' },
  { to: '/about', label: '关于' },
] as const
</script>

<template>
  <header class="site-header">
    <div class="shell header__inner">
      <RouterLink class="brand" to="/">
        <span class="brand__name">rag-mcp</span>
        <span class="brand__descriptor">证据检索</span>
      </RouterLink>
      <nav class="nav" aria-label="主导航">
        <RouterLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="nav__link"
          exact-active-class="nav__link--active"
        >
          {{ link.label }}
        </RouterLink>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.site-header {
  position: sticky;
  top: 0;
  z-index: 10;
  border-bottom: 1px solid var(--rule);
  background: var(--paper);
}

.header__inner {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 24px;
  min-height: 72px;
}

.brand {
  display: inline-flex;
  align-items: baseline;
  align-self: center;
  gap: 14px;
  text-decoration: none;
}

.brand__name {
  font-family: var(--font-display);
  font-size: 1.1875rem;
  font-weight: 700;
  letter-spacing: -0.035em;
  line-height: 1;
}

.brand__descriptor {
  padding-left: 14px;
  border-left: 1px solid var(--rule);
  color: var(--steel);
  font-size: 0.8125rem;
  line-height: 1.2;
}

.nav {
  display: flex;
  align-items: stretch;
  gap: 28px;
}

.nav__link {
  display: inline-flex;
  align-items: center;
  position: relative;
  color: var(--steel);
  font-size: 0.875rem;
  line-height: 1;
  text-decoration: none;
}

.nav__link:hover {
  color: var(--ink);
}

.nav__link--active {
  color: var(--ink);
}

.nav__link--active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--ink);
}

@media (max-width: 600px) {
  .header__inner {
    min-height: 64px;
  }

  .brand__descriptor {
    display: none;
  }

  .nav {
    gap: 20px;
  }

  .nav__link {
    font-size: 0.8125rem;
  }
}
</style>
