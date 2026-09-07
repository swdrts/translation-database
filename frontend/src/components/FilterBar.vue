<template>
  <div class="filter-bar">
    <div class="filter-row" v-if="facets.tags.length">
      <span class="filter-label">标签</span>
      <el-select
        v-model="model.tags"
        multiple
        clearable
        placeholder="全部标签"
        style="min-width: 280px"
        @change="emitChange"
      >
        <el-option v-for="t in facets.tags" :key="t.name" :label="`${t.name} (${t.count})`" :value="t.name" />
      </el-select>
    </div>
    <div class="filter-row" v-if="facets.dynasties.length">
      <span class="filter-label">朝代</span>
      <el-select
        v-model="model.dynasty"
        clearable
        placeholder="全部朝代"
        style="min-width: 180px"
        @change="emitChange"
      >
        <el-option v-for="d in facets.dynasties" :key="d.name" :label="`${d.name} (${d.count})`" :value="d.name" />
      </el-select>
    </div>
    <div class="filter-row" v-if="facets.works.length">
      <span class="filter-label">书名</span>
      <el-select
        v-model="model.work"
        clearable
        filterable
        placeholder="全部书名"
        style="min-width: 220px"
        @change="emitChange"
      >
        <el-option v-for="w in facets.works" :key="w.name" :label="`${w.name} (${w.count})`" :value="w.name" />
      </el-select>
    </div>
    <el-button v-if="hasFilter" link class="filter-reset" @click="reset">清除筛选</el-button>
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

function reset() {
  model.tags = []
  model.dynasty = ''
  model.work = ''
  emitChange()
}
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 20px; }
.filter-row { display: flex; align-items: center; gap: 8px; }
.filter-label { color: #3d3d3d; font-size: 14px; }
.filter-reset { color: #b03a2e; }
</style>
