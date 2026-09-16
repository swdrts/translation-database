<script setup lang="ts">
import { reactive, ref } from 'vue'
import { defaultWizardConfig, validateConfig } from './validate'
import type { WizardConfig } from './types'

const emit = defineEmits<{ (e: 'submit', cfg: WizardConfig): void }>()
const form = reactive({ ...defaultWizardConfig() })
const password2 = ref('')
const advancedOpen = ref(false)
const errors = ref<string[]>([])

function submit() {
  const errs = validateConfig(form)
  if (password2.value !== form.admin_password) errs.push('两次输入的管理员密码不一致')
  errors.value = errs
  if (errs.length === 0) emit('submit', { ...form })
}
</script>

<template>
  <el-form label-width="130px">
    <el-form-item label="网页访问端口">
      <el-input-number v-model="form.port" :min="1" :max="65535" data-test="port" />
    </el-form-item>
    <el-form-item label="管理员密码">
      <el-input v-model="form.admin_password" type="password" show-password class="admin-password" placeholder="至少 8 位" />
    </el-form-item>
    <el-form-item label="确认密码">
      <el-input v-model="password2" type="password" show-password class="admin-password2" />
    </el-form-item>
    <el-collapse v-model="advancedOpen">
      <el-collapse-item title="高级选项（一般无需修改）" name="adv">
        <el-form-item label="ES 堆内存 (GB)"><el-input-number v-model="form.es_heap_gb" :min="1" :max="16" /></el-form-item>
        <el-form-item label="后端内存 (GB)"><el-input-number v-model="form.backend_heap_gb" :min="1" :max="16" /></el-form-item>
        <el-form-item label="镜像版本"><el-input v-model="form.app_version" /></el-form-item>
      </el-collapse-item>
    </el-collapse>
    <el-alert v-for="e in errors" :key="e" :title="e" type="error" :closable="false" style="margin: 8px 0" />
    <el-button type="primary" class="submit" @click="submit">开始部署</el-button>
  </el-form>
</template>
