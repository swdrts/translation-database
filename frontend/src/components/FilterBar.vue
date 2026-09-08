<template>
  <div class="filter-bar">
    <p class="filter-tip">不想打字？点下面的标签，也能按类别浏览：</p>

    <!-- 标签：多选纸片，点亮即生效 -->
    <div class="chip-row" v-if="facets.tags.length">
      <span class="row-label">标签</span>
      <button
        v-for="t in facets.tags"
        :key="t.name"
        type="button"
        class="chip"
        :class="{ on: model.tags.includes(t.name) }"
        @click="toggleTag(t.name)"
      >
        {{ t.name }}<sup class="chip-count">{{ t.count }}</sup>
      </button>
    </div>

    <!-- 朝代：单选纸片，再点一次取消 -->
    <div class="chip-row" v-if="facets.dynasties.length">
      <span class="row-label">朝代</span>
      <button
        v-for="d in facets.dynasties"
        :key="d.name"
        type="button"
        class="chip"
        :class="{ on: model.dynasty === d.name }"
        @click="toggleDynasty(d.name)"
      >
        {{ d.name }}<sup class="chip-count">{{ d.count }}</sup>
      </button>
    </div>

    <!-- 书名：数量可能较多，保留下拉但用大白话 -->
    <div class="chip-row" v-if="facets.works.length">
      <span class="row-label">书目</span>
      <el-select
        v-model="model.work"
        clearable
        filterable
        placeholder="全部书目"
        class="work-select"
        size="large"
        @change="emitChange"
      >
        <el-option v-for="w in facets.works" :key="w.name" :label="`${w.name}（${w.count} 条）`" :value="w.name" />
      </el-select>
    </div>

    <button v-if="hasFilter" type="button" class="reset-btn" @click="reset">
      ✕ 清除全部筛选
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { Facets } from '../api'

const props = defineProps<{ facets: Facets; modelValue: { tags: string[]; dynasty: string; work: string } }>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: { tags: string[]; dynasty: string; work: string }): void
  (e: 'change'): void
}>()

const model = reactive(props.modelValue)
watch(
  () => props.modelValue,
  (v) => Object.assign(model, v),
  { deep: true }
)

const hasFilter = computed(
  () => model.tags.length > 0 || !!model.dynasty || !!model.work
)

function emitChange() {
  emit('update:modelValue', { tags: [...model.tags], dynasty: model.dynasty, work: model.work })
  emit('change')
}

function toggleTag(name: string) {
  model.tags = model.tags.includes(name)
    ? model.tags.filter((t) => t !== name)
    : [...model.tags, name]
  emitChange()
}

function toggleDynasty(name: string) {
  model.dynasty = model.dynasty === name ? '' : name
  emitChange()
}

function reset() {
  model.tags = []
  model.dynasty = ''
  model.work = ''
  emitChange()
}
</script>

<style scoped>
.filter-bar {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-tip {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-3);
  letter-spacing: 1px;
}

.chip-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 10px;
}
.row-label {
  font-family: var(--font-ui);
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-3);
  letter-spacing: 3px;
  margin-right: 2px;
  flex: none;
}

/* 纸片：像书签一样点亮 */
.chip {
  font-family: var(--font-ui);
  font-size: 14.5px;
  letter-spacing: 1px;
  color: var(--ink-2);
  background: #fdf8ec;
  border: 1px solid var(--card-edge);
  border-radius: 999px;
  padding: 7px 16px;
  cursor: pointer;
  transition: all 0.18s;
}
.chip:hover { border-color: var(--cinnabar-light, #c27b77); color: var(--cinnabar); }
.chip.on {
  background: var(--cinnabar);
  border-color: var(--cinnabar);
  color: #fdf4e3;
  box-shadow: 0 3px 10px rgba(168, 67, 60, 0.3);
}
.chip-count { font-size: 10.5px; margin-left: 3px; opacity: 0.75; }

.work-select { width: 260px; }

.reset-btn {
  align-self: flex-start;
  font-family: var(--font-ui);
  font-size: 13.5px;
  color: var(--cinnabar);
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 6px;
  letter-spacing: 1px;
}
.reset-btn:hover { text-decoration: underline; }
</style>
