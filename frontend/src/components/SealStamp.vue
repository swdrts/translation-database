<template>
  <span class="seal" :style="sealStyle" aria-hidden="true">{{ char }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ char?: string; size?: number }>(), {
  char: '译',
  size: 40
})

const sealStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  fontSize: `${Math.round(props.size * 0.56)}px`
}))
</script>

<style scoped>
/* 朱砂印章：仿篆刻印面的双圈纹样，作为全站品牌符号 */
.seal {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  font-family: var(--font-display);
  font-weight: 700;
  color: #fdf4e3;
  background: linear-gradient(145deg, #b04a3f 0%, #8f332c 100%);
  border-radius: 24%;
  box-shadow:
    inset 0 0 0 2px rgba(253, 244, 227, 0.6),
    inset 0 0 0 4px rgba(143, 51, 44, 0.9),
    0 2px 6px rgba(143, 51, 44, 0.35);
  user-select: none;
}
</style>
