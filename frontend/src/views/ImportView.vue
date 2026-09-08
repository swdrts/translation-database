<template>
  <div class="import-view page-narrow">
    <div class="import-card page-card rise">
      <!-- 入口：选择录入方式 -->
      <template v-if="mode === null">
        <h2 class="page-title">录入资料</h2>
        <p class="page-lead">想往数据库里添加内容？从下面挑一种最顺手的方式，跟着提示走就可以。</p>

        <div class="mode-grid">
          <button class="mode-card doc" type="button" data-test="mode-document" @click="mode = 'document'">
            <span class="mode-badge">推荐</span>
            <span class="mode-icon">📖</span>
            <span class="mode-name">导入整本书 / 文档</span>
            <span class="mode-desc">
              手里有现成的电子书（EPUB、PDF、Word、TXT 等）就选这个：系统自动拆成一段一段原文，
              译文先留空，之后逐条补写。
            </span>
            <span class="mode-cta">从这里开始 ›</span>
          </button>

          <button class="mode-card" type="button" data-test="mode-table" @click="mode = 'table'">
            <span class="mode-icon">📋</span>
            <span class="mode-name">导入对照表格</span>
            <span class="mode-desc">
              已经把「原文—译文」一句一句整理成表格（Excel / CSV / JSON）的进阶用法，
              一次导入就是现成的对照条目。
            </span>
            <span class="mode-cta">从这里开始 ›</span>
          </button>

          <button class="mode-card" type="button" data-test="mode-manual" @click="router.push('/segments/new')">
            <span class="mode-icon">✍️</span>
            <span class="mode-name">手动录入一条</span>
            <span class="mode-desc">
              只想补一两句话？直接打字或粘贴进来，译文现在没有也可以先存草稿。
            </span>
            <span class="mode-cta">去填写 ›</span>
          </button>
        </div>
      </template>

      <!-- 各方式面板 -->
      <DocumentImportPanel v-else-if="mode === 'document'" @back="mode = null" />
      <TableImportPanel v-else @back="mode = null" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import DocumentImportPanel from '../components/DocumentImportPanel.vue'
import TableImportPanel from '../components/TableImportPanel.vue'

const router = useRouter()
const mode = ref<'document' | 'table' | null>(null)

defineExpose({ mode })
</script>

<style scoped>
.import-view { padding-top: 8px; }
.import-card { padding: 30px 36px; }

.mode-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 16px;
  margin-top: 26px;
}
@media (max-width: 900px) {
  .mode-grid { grid-template-columns: 1fr; }
}

.mode-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  text-align: left;
  padding: 22px 20px 18px;
  border: 1.5px solid var(--card-edge);
  border-radius: 16px;
  background: var(--el-fill-color-lighter);
  cursor: pointer;
  transition: border-color 0.2s, transform 0.15s, box-shadow 0.2s;
  font-family: var(--font-ui);
}
.mode-card:hover {
  border-color: var(--cinnabar);
  transform: translateY(-3px);
  box-shadow: 0 10px 24px rgba(168, 67, 60, 0.14);
}
.mode-card.doc { border-color: rgba(168, 67, 60, 0.45); }

.mode-badge {
  position: absolute;
  top: -10px;
  right: 14px;
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 2px;
  color: #fdf4e3;
  background: var(--cinnabar);
  border-radius: 999px;
  padding: 2px 12px;
}
.mode-icon { font-size: 30px; line-height: 1; }
.mode-name {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
}
.mode-desc {
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-3);
}
.mode-cta {
  margin-top: auto;
  padding-top: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--cinnabar);
}
</style>
