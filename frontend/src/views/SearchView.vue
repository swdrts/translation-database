<template>
  <div class="search-view">
    <!-- ---------- 搜索区 ---------- -->
    <div class="search-hero">
      <h1 class="hero-title rise">翻译学术数据库</h1>
      <p class="hero-lead rise d1">想找一句古文或它的英文翻译？<br />直接在下面输入，中文、英文、拼音首字母都可以。</p>

      <div class="search-box rise d2">
        <el-input
          v-model="q"
          size="large"
          placeholder="例如：学而时习之 / 论语 / Confucius"
          clearable
          data-test="search-input"
          class="search-input"
          @input="onInput"
          @keyup.enter="doSearch"
          @focus="suggestVisible = true"
        >
          <template #prefix><el-icon :size="19"><Search /></el-icon></template>
        </el-input>
        <el-button
          type="primary"
          size="large"
          class="search-btn"
          :loading="loading"
          data-test="search-btn"
          @click="doSearch"
        >
          搜 索
        </el-button>

        <div v-if="showSuggest" class="suggest-panel">
          <template v-if="suggestGroups.length">
            <div v-for="g in suggestGroups" :key="g.label" class="suggest-group">
              <div class="suggest-label">{{ g.label }}</div>
              <button
                v-for="s in g.items"
                :key="g.label + s"
                type="button"
                class="suggest-item"
                @click="applySuggest(s)"
              >{{ s }}</button>
            </div>
          </template>
          <div v-else class="suggest-empty">没有找到相关联想，直接按「搜索」试试</div>
        </div>
      </div>

      <div class="example-row rise d3">
        <span class="example-label">试试搜：</span>
        <button v-for="e in examples" :key="e" type="button" class="example-chip" @click="searchExample(e)">
          {{ e }}
        </button>
      </div>

      <div class="hero-actions rise d3" v-if="auth.isEditor">
        <el-button plain size="large" data-test="new-segment-btn" @click="router.push('/segments/new')">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>手动录入一条
        </el-button>
      </div>
    </div>

    <!-- ---------- 降级提示 ---------- -->
    <el-alert
      v-if="degraded"
      class="degraded-banner rise"
      type="warning"
      :closable="false"
      show-icon
      title="当前为简化搜索模式：高级搜索暂时休息，普通搜索不受影响，请放心使用"
    />

    <!-- ---------- 筛选区 ---------- -->
    <div class="filter-card page-card rise d3" v-if="hasAnyFacet">
      <div class="filter-inner">
        <FilterBar v-model="filters" :facets="facets" @change="onFilterChange" />
      </div>
    </div>

    <!-- ---------- 结果统计 ---------- -->
    <div class="result-summary rise d4" v-if="searched && !loading">
      <template v-if="results.total > 0">
        共找到 <b class="count">{{ results.total }}</b> 条
        <span v-if="q.trim()">与「{{ q.trim() }}」相关的内容</span>
        <span v-else>内容</span>，点击卡片可查看完整对照
      </template>
    </div>

    <!-- ---------- 空状态 ---------- -->
    <div v-if="!loading && searched && results.content.length === 0" class="empty-hint page-card rise d4">
      <div class="empty-glyph" aria-hidden="true">卷</div>
      <h3 class="empty-title">没有找到相关内容</h3>
      <p class="empty-lead">
        别担心，可以换个说法再试试：<br />
        · 用更短的关键词（如「学而」代替整句）<br />
        · 或者清除筛选条件，浏览全部内容
      </p>
      <el-button type="primary" plain size="large" @click="clearAndBrowse">清除筛选，浏览全部</el-button>
    </div>

    <!-- ---------- 结果列表 ---------- -->
    <div class="result-list" v-loading="loading">
      <SearchResultCard v-for="item in results.content" :key="item.id" :item="item" class="rise" />
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
import { Plus, Search } from '@element-plus/icons-vue'
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

// 新手引导：预设示例搜索词，一键体验
const examples = ['学而时习之', '论语', 'Confucius', '有朋自远方来']

const hasAnyFacet = computed(
  () => facets.value.tags.length + facets.value.dynasties.length + facets.value.works.length > 0
)

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

function searchExample(text: string) {
  q.value = text
  suggestVisible.value = false
  doSearch()
}

/** 空状态引导：清空关键词与筛选，回到全部内容 */
function clearAndBrowse() {
  q.value = ''
  filters.value = { tags: [], dynasty: '', work: '' }
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
  // 翻页后回到结果顶部，避免新手迷失位置
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
</script>

<style scoped>
.search-view { max-width: 880px; margin: 0 auto; }

/* ---------- 搜索主视觉 ---------- */
.search-hero { text-align: center; padding: 44px 0 28px; }
.hero-title {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 40px;
  letter-spacing: 10px;
  color: var(--ink);
  margin: 0 0 14px;
  text-indent: 10px; /* 抵消末字距，保证视觉居中 */
}
.hero-lead {
  color: var(--ink-3);
  font-size: 15.5px;
  line-height: 2;
  margin: 0 0 28px;
}

.search-box {
  position: relative;
  display: flex;
  gap: 12px;
  max-width: 660px;
  margin: 0 auto;
}
.search-input { flex: 1; }
.search-input :deep(.el-input__wrapper) {
  border-radius: 14px;
  padding: 6px 16px;
  box-shadow: 0 0 0 1px var(--card-edge) inset, 0 4px 14px rgba(87, 68, 43, 0.08);
}
.search-input :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--cinnabar) inset, 0 6px 18px rgba(168, 67, 60, 0.14);
}
.search-btn {
  border-radius: 14px;
  letter-spacing: 4px;
  padding: 0 30px;
  box-shadow: 0 6px 18px rgba(168, 67, 60, 0.32);
}

/* 联想面板 */
.suggest-panel {
  position: absolute;
  z-index: 30;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  background: var(--card);
  border: 1px solid var(--card-edge);
  border-radius: 14px;
  box-shadow: 0 14px 36px rgba(87, 68, 43, 0.18);
  padding: 10px 0;
  text-align: left;
}
.suggest-label {
  font-family: var(--font-ui);
  font-size: 12px;
  color: var(--cinnabar);
  letter-spacing: 3px;
  padding: 8px 18px 3px;
}
.suggest-item {
  display: block;
  width: 100%;
  text-align: left;
  font-family: var(--font-serif);
  font-size: 15px;
  color: var(--ink);
  background: none;
  border: none;
  padding: 8px 18px;
  cursor: pointer;
}
.suggest-item:hover { background: var(--el-fill-color-light); }
.suggest-empty { padding: 12px 18px; color: var(--ink-3); font-size: 13.5px; }

/* 示例搜索词 */
.example-row {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 20px;
}
.example-label { font-size: 13.5px; color: var(--ink-3); letter-spacing: 1px; }
.example-chip {
  font-family: var(--font-ui);
  font-size: 13.5px;
  color: var(--cinnabar);
  background: rgba(168, 67, 60, 0.06);
  border: 1px dashed rgba(168, 67, 60, 0.4);
  border-radius: 999px;
  padding: 5px 14px;
  cursor: pointer;
  transition: all 0.18s;
}
.example-chip:hover { background: rgba(168, 67, 60, 0.14); }

.hero-actions { margin-top: 18px; }

/* ---------- 筛选卡片 ---------- */
.degraded-banner { margin-bottom: 16px; border-radius: 12px; }
.filter-card { margin-bottom: 20px; }
.filter-inner { padding: 18px 22px; }

/* ---------- 结果统计 ---------- */
.result-summary {
  font-family: var(--font-ui);
  font-size: 14px;
  color: var(--ink-3);
  margin: 4px 2px 14px;
}
.result-summary .count {
  color: var(--cinnabar);
  font-size: 17px;
  margin: 0 2px;
}

/* ---------- 空状态 ---------- */
.empty-hint { padding: 40px 24px; text-align: center; }
.empty-glyph {
  width: 72px;
  height: 72px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-display);
  font-size: 34px;
  color: var(--cinnabar);
  background: rgba(168, 67, 60, 0.07);
  border: 1px dashed rgba(168, 67, 60, 0.35);
  border-radius: 50%;
}
.empty-title {
  font-family: var(--font-display);
  font-size: 20px;
  letter-spacing: 3px;
  color: var(--ink);
  margin: 0 0 10px;
}
.empty-lead {
  color: var(--ink-3);
  font-size: 14px;
  line-height: 2.1;
  margin: 0 0 20px;
}

/* ---------- 结果列表与分页 ---------- */
.result-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 120px;
}
.pager { display: flex; justify-content: center; padding: 28px 0 12px; }

@media (max-width: 640px) {
  .hero-title { font-size: 30px; letter-spacing: 6px; }
  .search-box { flex-direction: column; }
  .search-btn { width: 100%; }
}
</style>
