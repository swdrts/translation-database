<template>
  <article class="result-card" @click="$router.push(`/segments/${item.id}`)">
    <div class="card-top">
      <span class="field-badge source">原文</span>
      <!-- 仅当 highlight 返回 source_text 片段时用 v-html（<em> 由服务端控制） -->
      <p class="source-text" v-if="item.highlight?.source_text?.length" v-html="joinHighlight(item.highlight.source_text)" />
      <p class="source-text" v-else>{{ item.sourceText }}</p>
    </div>

    <div class="card-top">
      <span class="field-badge target">译文</span>
      <p class="translated-text" v-if="item.highlight.translated_text?.length" v-html="joinHighlight(item.highlight.translated_text)" />
      <p class="translated-text" v-else>{{ item.translatedText }}</p>
    </div>

    <div class="meta-line">
      <span class="work-title" v-if="item.workTitle">《{{ item.workTitle }}》</span>
      <span v-if="item.chapter" class="chapter">{{ item.chapter }}</span>
      <span v-if="item.author" class="author">{{ item.dynasty ? item.dynasty + ' · ' : '' }}{{ item.author }}</span>
      <span v-if="item.translator" class="translator">译：{{ item.translator }}</span>
    </div>

    <div class="tag-chips" v-if="item.tags.length">
      <span v-for="t in item.tags" :key="t" class="tag-chip" @click.stop>{{ t }}</span>
    </div>
  </article>
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
  position: relative;
  background: var(--card);
  border: 1px solid var(--card-edge);
  border-radius: 14px;
  padding: 20px 24px 16px;
  cursor: pointer;
  transition: transform 0.18s, box-shadow 0.18s, border-color 0.18s;
}
.result-card:hover {
  transform: translateY(-2px);
  border-color: rgba(168, 67, 60, 0.4);
  box-shadow: 0 10px 26px rgba(87, 68, 43, 0.13);
}
/* 左侧朱砂细条：书脊意象 */
.result-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  bottom: 14px;
  width: 3px;
  border-radius: 3px;
  background: linear-gradient(180deg, var(--cinnabar), rgba(168, 67, 60, 0.25));
  opacity: 0;
  transition: opacity 0.18s;
}
.result-card:hover::before { opacity: 1; }

.card-top { display: flex; align-items: flex-start; gap: 12px; }
.card-top .field-badge { margin-top: 5px; }

.source-text {
  flex: 1;
  font-family: var(--font-display);
  font-size: 18.5px;
  color: var(--ink);
  margin: 0 0 10px;
  line-height: 1.75;
  letter-spacing: 1px;
}
.source-text :deep(em) {
  font-style: normal;
  font-weight: 700;
  color: var(--cinnabar);
  background: rgba(168, 67, 60, 0.12);
  border-radius: 4px;
  padding: 0 3px;
}
.translated-text {
  flex: 1;
  font-size: 15px;
  color: var(--ink-2);
  margin: 0 0 12px;
  line-height: 1.75;
}
.translated-text :deep(em) {
  font-style: normal;
  font-weight: 700;
  color: var(--verdigris);
  background: rgba(61, 107, 99, 0.12);
  border-radius: 4px;
  padding: 0 3px;
}

.meta-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  font-family: var(--font-ui);
  font-size: 13px;
  color: var(--ink-3);
  padding-top: 10px;
  border-top: 1px dashed var(--card-edge);
}
.work-title { color: var(--cinnabar); font-weight: 600; letter-spacing: 1px; }

.tag-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.tag-chip {
  font-family: var(--font-ui);
  font-size: 12px;
  letter-spacing: 1px;
  color: #8a6d1f;
  background: rgba(185, 138, 47, 0.1);
  border: 1px solid rgba(185, 138, 47, 0.3);
  border-radius: 6px;
  padding: 2px 9px;
}
</style>
