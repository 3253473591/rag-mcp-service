<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    code: string
    message: string
    traceId: string
    status?: number
    next?: string
  }>(),
  { title: '请求没有完成', status: undefined, next: '' },
)
</script>

<template>
  <section class="panel-error" role="alert">
    <h2 class="panel-error__title">{{ title }}</h2>
    <p class="panel-error__body">{{ message }}</p>
    <dl class="panel-error__meta">
      <div>
        <dt class="sr-only">错误代码</dt>
        <dd>code&nbsp;&nbsp;{{ code }}</dd>
      </div>
      <div v-if="status !== undefined">
        <dt class="sr-only">HTTP 状态</dt>
        <dd>http&nbsp;&nbsp;{{ status }}</dd>
      </div>
      <div>
        <dt class="sr-only">追踪编号</dt>
        <dd>trace&nbsp;{{ traceId }}</dd>
      </div>
    </dl>
    <p v-if="next" class="panel-error__next">{{ next }}</p>
    <div v-if="$slots.actions" class="panel-error__actions">
      <slot name="actions" />
    </div>
  </section>
</template>

<style scoped>
.panel-error__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}
</style>
