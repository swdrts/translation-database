<template>
  <div class="search-view">
    <div class="search-hero">
      <h1 class="hero-title">翻译学术数据库</h1>
      <div class="hero-actions" v-if="auth.isEditor">
        <el-button type="primary" plain data-test="new-segment-btn" @click="$router.push('/segments/new')">新建条目</el-button>
      </div>
      <div class="search-box">
        <el-input
          v-model="q"
          size="large"
          placeholder="搜索原文、译文、书名、作者…"
          clearable
          data-test="search-input"
          @input="onInput"
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button :loading="loading" data-test="search-btn" @click="doSearch">搜索</el-button>
          </template>
        </el-input>
        <div v-if="showSuggest" class="suggest-panel">
          <template v-if="suggestGroups.length">
            <div v-for="g in suggestGroups" :key="g.label" class="suggest-group">
              <div class="suggest-label">{{ g.label }}</div>
              <div
                v-for="s in g.items"
                :key="g.label + s"
                class="suggest-item"
                @click="applySuggest(s)"
              >{{ s }}</div>
            </div>
          </template>
          <div v-else class="suggest-empty">无匹配联想</div>
        </div>
      </div>
    </div>

    <el-alert
      v-if="degraded"
      class="degraded-banner"
      type="warning"
      :closable="false"
      show-icon
      title="ES 不可用，当前为简化搜索模式"
    />

    <div class="filter-card">
      <FilterBar v-model="filters" :facets="facets" @change="onFilterChange" />
    </div>

    <div v-if="!loading && searched && results.content.length === 0" class="empty-hint">
      <el-empty description="没有找到相关条目" />
    </div>

    <div class="result-list">
      <SearchResultCard v-for="item in results.content" :key="item.id" :item="item" />
    </div>

    <div class="pager" v-if="results.total > results.size">
      <el-pagination
        layout="prev, pager, next"
        :total="results.total"
        :page-size="results.size"
        :current-page="page + 1"
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, type Facets, type SearchResult, type SuggestResult } from '../api'
import { useAuthStore } from '../stores/auth'
import FilterBar from '../components/FilterBar.vue'
import SearchResultCard from '../components/SearchResultCard.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const q = ref('')
const filters = ref({ tags: [] as string[], dynasty: '', work: '' })
const page = ref(0)
const loading = ref(false)
const searched = ref(false)
const degraded = ref(false)
const facets = ref<Facets>({ tags: [], dynasties: [], works: [] })
const results = ref<SearchResult>({ content: [], total: 0, page: 0, size: 20, degraded: false, facets: { tags: [], dynasties: [], works: [] } })

// 联想
const suggest = ref<SuggestResult>({ works: [], authors: [], tags: [] })
const suggestVisible = ref(false)
let suggestTimer: ReturnType<typeof setTimeout> | null = null

const suggestGroups = computed(() => [
  { label: '书名', items: suggest.value.works },
  { label: '作者', items: suggest.value.authors },
  { label: '标签', items: suggest.value.tags }
].filter((g) => g.items.length > 0))
const showSuggest = computed(() => suggestVisible.value && q.value.trim().length > 0)

onMounted(async () => {
  // URL query 还原（q/tags/dynasty/work/page），刷新可复现
  const query = route.query
  if (typeof query.q === 'string') q.value = query.q
  if (typeof query.tags === 'string' && query.tags) filters.value.tags = query.tags.split(',')
  if (typeof query.dynasty === 'string') filters.value.dynasty = query.dynasty
  if (typeof query.work === 'string') filters.value.work = query.work
  if (typeof query.page === 'string') page.value = Math.max(0, parseInt(query.page, 10) - 1 || 0)
  try {
    facets.value = await api.facets()
  } catch {
    /* facets 失败不阻塞搜索 */
  }
  await search()
})

function onInput() {
  if (suggestTimer) clearTimeout(suggestTimer)
  suggestTimer = setTimeout(fetchSuggest, 300)
}

async function fetchSuggest() {
  const text = q.value.trim()
  if (!text) {
    suggestVisible.value = false
    return
  }
  try {
    suggest.value = await api.suggest(text)
    suggestVisible.value = true
  } catch {
    suggestVisible.value = false
  }
}

function applySuggest(text: string) {
  q.value = text
  suggestVisible.value = false
  doSearch()
}

function buildParams() {
  return {
    q: q.value.trim() || undefined,
    tags: filters.value.tags.join(',') || undefined,
    dynasty: filters.value.dynasty || undefined,
    work: filters.value.work || undefined,
    page: page.value,
    size: 20
  }
}

function syncQuery() {
  const p = buildParams()
  const query: Record<string, string> = {}
  if (p.q) query.q = p.q
  if (p.tags) query.tags = p.tags
  if (p.dynasty) query.dynasty = p.dynasty
  if (p.work) query.work = p.work
  if (page.value > 0) query.page = String(page.value + 1)
  router.push({ path: '/', query })
}

async function search() {
  loading.value = true
  try {
    const r = await api.search(buildParams())
    results.value = r
    degraded.value = r.degraded
    if (r.facets) facets.value = r.facets
    searched.value = true
  } finally {
    loading.value = false
  }
}

function doSearch() {
  page.value = 0
  suggestVisible.value = false
  syncQuery()
  search()
}

function onFilterChange() {
  page.value = 0
  syncQuery()
  search()
}

function onPageChange(p: number) {
  page.value = p - 1
  syncQuery()
  search()
}
</script>

<style scoped>
.search-view { max-width: 860px; margin: 0 auto; }
.search-hero { text-align: center; padding: 40px 0 24px; }
.hero-title { color: #3d3d3d; font-weight: 700; letter-spacing: 4px; margin-bottom: 12px; }
.hero-actions { margin-bottom: 16px; }
.search-box { position: relative; max-width: 620px; margin: 0 auto; }
.search-box :deep(.el-input-group__append) { background: #b03a2e; border-color: #b03a2e; }
.search-box :deep(.el-input-group__append .el-button) { color: #fff; font-weight: 600; }
.suggest-panel {
  position: absolute;
  z-index: 30;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(61, 61, 61, 0.15);
  padding: 8px 0;
  text-align: left;
}
.suggest-label { font-size: 12px; color: #b03a2e; padding: 6px 16px 2px; }
.suggest-item { padding: 6px 16px; cursor: pointer; color: #3d3d3d; font-size: 14px; }
.suggest-item:hover { background: #f7f4ef; }
.suggest-empty { padding: 10px 16px; color: #8a8378; font-size: 13px; }
.degraded-banner { margin-bottom: 16px; }
.filter-card { background: #fff; border-radius: 10px; padding: 14px 20px; margin-bottom: 16px; }
.empty-hint { padding: 32px 0; }
.result-list { display: flex; flex-direction: column; gap: 14px; }
.pager { display: flex; justify-content: center; padding: 24px 0; }
</style>
