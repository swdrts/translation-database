<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { defaultWizardConfig, validateConfig } from './validate'
import type { WizardConfig } from './types'

const props = defineProps<{ initialPort?: number }>()
const emit = defineEmits<{ (e: 'submit', cfg: WizardConfig): void }>()
const form = reactive({ ...defaultWizardConfig(), ...(props.initialPort ? { port: props.initialPort } : {}) })
const password2 = ref('')
const advancedOpen = ref<string[]>([])
const errors = ref<string[]>([])

// 密码规则实时提示：边输入边显示，不再等提交才暴露
const pwdChecks = computed(() => [
  { ok: form.admin_password.length >= 8, text: '至少 8 位' },
  { ok: !/[\s#$'"]/.test(form.admin_password), text: '不含空格、#、$ 或引号' },
  { ok: password2.value !== '' && password2.value === form.admin_password, text: '两次输入一致' },
])

function submit() {
  const errs = validateConfig(form)
  if (password2.value !== form.admin_password) errs.push('两次输入的管理员密码不一致')
  errors.value = errs
  if (errs.length === 0) emit('submit', { ...form })
}
</script>

<template>
  <h3 style="margin: 0 0 4px">设置管理员密码</h3>
  <p style="color: #909399; margin-top: 0">这是你登录网页时使用的密码（账号固定为 admin），其余设置均有默认值，可直接开始安装。</p>
  <el-form label-width="130px">
    <el-form-item label="管理员密码">
      <el-input v-model="form.admin_password" type="password" show-password class="admin-password" placeholder="至少 8 位" />
    </el-form-item>
    <el-form-item label="确认密码">
      <el-input v-model="password2" type="password" show-password class="admin-password2" placeholder="再输入一次" />
    </el-form-item>
    <el-form-item label=" ">
      <ul style="list-style: none; padding: 0; margin: 0">
        <li v-for="c in pwdChecks" :key="c.text" :style="{ color: c.ok ? '#67c23a' : '#909399', fontSize: '13px' }">
          {{ c.ok ? '✓' : '·' }} {{ c.text }}
        </li>
      </ul>
    </el-form-item>
    <el-collapse v-model="advancedOpen">
      <el-collapse-item title="更多设置（一般无需修改）" name="adv">
        <el-form-item label="网页访问端口">
          <el-input-number v-model="form.port" :min="1" :max="65535" data-test="port" />
        </el-form-item>
        <el-form-item label="ES 堆内存 (GB)"><el-input-number v-model="form.es_heap_gb" :min="1" :max="16" /></el-form-item>
        <el-form-item label="后端内存 (GB)"><el-input-number v-model="form.backend_heap_gb" :min="1" :max="16" /></el-form-item>
        <el-form-item label="镜像版本"><el-input v-model="form.app_version" /></el-form-item>
      </el-collapse-item>
    </el-collapse>
    <el-alert v-for="e in errors" :key="e" :title="e" type="error" :closable="false" style="margin: 8px 0" />
    <el-button type="primary" size="large" class="submit" @click="submit">开始安装</el-button>
  </el-form>
</template>
