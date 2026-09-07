<template>
  <div class="detail-view" v-if="segment">
    <div class="detail-card">
      <div class="card-head">
        <h2 class="detail-title">{{ segment.workTitle || '未分类条目' }}<span v-if="segment.chapter"> · {{ segment.chapter }}</span></h2>
        <div class="actions">
          <el-button v-if="auth.isEditor" type="primary" plain data-test="edit-btn" @click="$router.push(`/segments/${segment.id}/edit`)">编辑</el-button>
          <el-button v-if="auth.isAdmin" type="danger" plain data-test="delete-btn" @click="onDelete">删除</el-button>
        </div>
      </div>

      <el-descriptions :column="2" border class="detail-desc">
        <el-descriptions-item label="原文" :span="2"><p class="source-text">{{ segment.sourceText }}</p></el-descriptions-item>
        <el-descriptions-item label="译文" :span="2"><p class="translated-text">{{ segment.translatedText }}</p></el-descriptions-item>
        <el-descriptions-item label="书名">{{ segment.workTitle || '—' }}</el-descriptions-item>
        <el-descriptions-item label="章节">{{ segment.chapter || '—' }}</el-descriptions-item>
        <el-descriptions-item label="作者">{{ segment.author || '—' }}</el-descriptions-item>
        <el-descriptions-item label="朝代">{{ segment.dynasty || '—' }}</el-descriptions-item>
        <el-descriptions-item label="译者">{{ segment.translator || '—' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="segment.status === 'PUBLISHED' ? 'success' : 'info'" size="small">
            {{ segment.status === 'PUBLISHED' ? '已发布' : '草稿' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ segment.notes || '—' }}</el-descriptions-item>
        <el-descriptions-item label="标签" :span="2">
          <template v-if="segment.tags.length">
            <el-tag v-for="t in segment.tags" :key="t" size="small" class="tag-chip">{{ t }}</el-tag>
          </template>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="更新时间" :span="2">{{ formatTime(segment.updatedAt) }}（版本 v{{ segment.version }}）</el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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
    await ElMessageBox.confirm('确定删除该条目？删除后不可恢复。', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
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
.detail-view { max-width: 860px; margin: 0 auto; padding-top: 24px; }
.detail-card { background: #fff; border-radius: 10px; padding: 24px 28px; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.detail-title { color: #3d3d3d; margin: 0; font-size: 20px; }
.source-text { font-size: 17px; line-height: 1.8; color: #3d3d3d; margin: 0; }
.translated-text { line-height: 1.7; color: #6b6b6b; margin: 0; }
.tag-chip { margin-right: 6px; background: #f7f4ef; border-color: #e5ded2; color: #3d3d3d; }
</style>
