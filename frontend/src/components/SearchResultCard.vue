<template>
  <div class="result-card" @click="$router.push(`/segments/${item.id}`)">
    <!-- 原文在上：仅当 highlight 返回 source_text 片段时用 v-html -->
    <p class="source-text" v-if="item.highlight?.source_text?.length" v-html="joinHighlight(item.highlight.source_text)" />
    <p class="source-text" v-else>{{ item.sourceText }}</p>
    <!-- 译文在下 -->
    <p class="translated-text" v-if="item.highlight.translated_text?.length" v-html="joinHighlight(item.highlight.translated_text)" />
    <p class="translated-text" v-else>{{ item.translatedText }}</p>
    <div class="meta-line">
      <span class="work-title" v-if="item.workTitle">{{ item.workTitle }}</span>
      <span v-if="item.chapter" class="chapter">{{ item.chapter }}</span>
      <span v-if="item.author" class="author">{{ item.dynasty ? item.dynasty + ' · ' : '' }}{{ item.author }}</span>
      <span v-if="item.translator" class="translator">译：{{ item.translator }}</span>
    </div>
    <div class="tag-chips" v-if="item.tags.length">
      <el-tag v-for="t in item.tags" :key="t" size="small" class="tag-chip" @click.stop>{{ t }}</el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { SearchItem } from '../api'

defineProps<{ item: SearchItem }>()

/**
 * 仅渲染后端返回的 highlight 片段（其中 <em> 由服务端控制）；
 * 其余任何用户文本一律走插值纯文本渲染，避免 XSS。
 */
function joinHighlight(frags: string[]): string {
  return frags.join(' … ')
}
</script>

<style scoped>
.result-card {
  background: #fff;
  border-radius: 10px;
  padding: 18px 22px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: box-shadow 0.2s;
}
.result-card:hover { box-shadow: 0 2px 12px rgba(61, 61, 61, 0.12); }
.source-text { font-size: 17px; color: #3d3d3d; margin: 0 0 8px; line-height: 1.7; }
.source-text :deep(em) { color: #b03a2e; font-style: normal; font-weight: 700; }
.translated-text { font-size: 14px; color: #6b6b6b; margin: 0 0 10px; line-height: 1.6; }
.meta-line { display: flex; flex-wrap: wrap; gap: 10px; font-size: 13px; color: #8a8378; margin-bottom: 8px; }
.work-title { color: #b03a2e; font-weight: 600; }
.tag-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.tag-chip { background: #f7f4ef; border-color: #e5ded2; color: #3d3d3d; }
</style>
