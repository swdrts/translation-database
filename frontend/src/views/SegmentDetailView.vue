<template>
  <div class="detail-view page-narrow" v-if="segment">
    <button class="back-btn rise" type="button" @click="router.back()">
      <el-icon><ArrowLeft /></el-icon> 返回上一页
    </button>

    <div class="detail-card page-card rise d1">
      <!-- 书名题头 -->
      <header class="book-head">
        <h2 class="book-title">{{ segment.workTitle || '未分类条目' }}</h2>
        <p class="book-sub" v-if="segment.chapter">{{ segment.chapter }}</p>
        <div class="book-actions">
          <el-button v-if="auth.isEditor" type="primary" plain data-test="edit-btn" @click="router.push(`/segments/${segment.id}/edit`)">
            <el-icon style="margin-right: 5px"><Edit /></el-icon>编辑这条内容
          </el-button>
          <el-button v-if="auth.isAdmin" type="danger" plain data-test="delete-btn" @click="onDelete">删除</el-button>
        </div>
      </header>

      <!-- 原文 / 译文 对照：书页式排版 -->
      <section class="passage source-passage">
        <span class="field-badge source">原文</span>
        <p class="source-text">{{ segment.sourceText }}</p>
      </section>

      <div class="ornament" aria-hidden="true"><span>❖</span></div>

      <section class="passage target-passage">
        <span class="field-badge target">译文</span>
        <p class="translated-text">{{ segment.translatedText }}</p>
      </section>

      <!-- 信息区：小卡片网格，大白话字段名 -->
      <section class="info-grid">
        <div class="info-item">
          <span class="info-label">作者</span>
          <span class="info-value">{{ segment.author || '—' }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">朝代</span>
          <span class="info-value">{{ segment.dynasty || '—' }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">译者</span>
          <span class="info-value">{{ segment.translator || '—' }}</span>
        </div>
        <div class="info-item">
          <span class="info-label">状态</span>
          <span class="info-value">
            <span class="status-dot" :class="segment.status === 'PUBLISHED' ? 'ok' : 'draft'"></span>
            {{ segment.status === 'PUBLISHED' ? '已发布（搜索可见）' : '草稿（仅内部可见）' }}
          </span>
        </div>
        <div class="info-item wide" v-if="segment.notes">
          <span class="info-label">备注</span>
          <span class="info-value">{{ segment.notes }}</span>
        </div>
        <div class="info-item wide">
          <span class="info-label">标签</span>
          <span class="info-value">
            <template v-if="segment.tags.length">
              <span v-for="t in segment.tags" :key="t" class="tag-chip">{{ t }}</span>
            </template>
            <span v-else>—</span>
          </span>
        </div>
        <div class="info-item wide">
          <span class="info-label">更新时间</span>
          <span class="info-value">{{ formatTime(segment.updatedAt) }}（第 {{ segment.version }} 次修订）</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Edit } from '@element-plus/icons-vue'
import { api, type SegmentVO } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const segment = ref<SegmentVO | null>(null)

onMounted(load)

async function load() {
  try {
    segment.value = await api.getSegment(Number(route.params.id))
  } catch {
    /* 错误提示由 http 拦截器统一处理 */
  }
}

async function onDelete() {
  if (!segment.value) return
  try {
    await ElMessageBox.confirm(
      '删除后这条内容就无法恢复了，确定要删除吗？',
      '删除前请确认',
      {
        type: 'warning',
        confirmButtonText: '确定删除',
        cancelButtonText: '先不删了'
      }
    )
  } catch {
    return
  }
  await api.deleteSegment(segment.value.id)
  ElMessage.success('已删除')
  router.push('/')
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.detail-view { padding-top: 8px; }

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-ui);
  font-size: 14px;
  color: var(--ink-3);
  background: none;
  border: none;
  cursor: pointer;
  padding: 6px 8px;
  margin-bottom: 14px;
  border-radius: 8px;
  transition: color 0.2s;
}
.back-btn:hover { color: var(--cinnabar); }

.detail-card { padding: 34px 40px 30px; }

/* ---------- 题头 ---------- */
.book-head {
  text-align: center;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--card-edge);
  position: relative;
}
.book-title {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 5px;
  color: var(--ink);
  margin: 0;
}
.book-sub {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--ink-3);
  letter-spacing: 3px;
}
.book-actions {
  position: absolute;
  right: 0;
  top: 0;
  display: flex;
  gap: 8px;
}

/* ---------- 原文 / 译文书页 ---------- */
.passage { display: flex; flex-direction: column; gap: 14px; padding: 28px 8px 8px; }
.source-text {
  font-family: var(--font-display);
  font-size: 24px;
  line-height: 2;
  letter-spacing: 2px;
  color: var(--ink);
  margin: 0;
  text-align: center;
}
.translated-text {
  font-size: 17px;
  line-height: 1.95;
  color: var(--ink-2);
  margin: 0;
}

/* 华文分隔饰线 */
.ornament {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 18px 0 4px;
  color: rgba(168, 67, 60, 0.6);
  font-size: 13px;
}
.ornament::before,
.ornament::after {
  content: '';
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--card-edge) 30%, var(--card-edge) 70%, transparent);
}

/* ---------- 信息网格 ---------- */
.info-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 26px;
  padding-top: 22px;
  border-top: 1px dashed var(--card-edge);
}
.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--card-edge);
  border-radius: 10px;
  padding: 10px 14px;
}
.info-item.wide { grid-column: span 3; }
.info-label {
  font-family: var(--font-ui);
  font-size: 12px;
  letter-spacing: 3px;
  color: var(--ink-3);
}
.info-value {
  font-size: 14.5px;
  color: var(--ink-2);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.status-dot.ok { background: var(--verdigris); box-shadow: 0 0 0 3px rgba(61, 107, 99, 0.18); }
.status-dot.draft { background: var(--ochre); box-shadow: 0 0 0 3px rgba(185, 138, 47, 0.18); }

.tag-chip {
  font-family: var(--font-ui);
  font-size: 12.5px;
  letter-spacing: 1px;
  color: #8a6d1f;
  background: rgba(185, 138, 47, 0.1);
  border: 1px solid rgba(185, 138, 47, 0.3);
  border-radius: 6px;
  padding: 2px 9px;
}

@media (max-width: 640px) {
  .detail-card { padding: 24px 18px; }
  .info-grid { grid-template-columns: 1fr 1fr; }
  .info-item.wide { grid-column: span 2; }
  .book-actions { position: static; justify-content: center; margin-top: 14px; }
  .source-text { font-size: 20px; }
}
</style>
