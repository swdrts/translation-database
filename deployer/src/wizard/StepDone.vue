<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { openWeb, openDashboardWindow } from '../api/deployer'
// reused=true：保留数据重部署，后端复用了旧 .env 三密钥——本轮输入的密码未生效，
// 只提示「沿用首次部署所设」，不展示本轮输入、不给复制按钮（复制必致登录失败）
const props = withDefaults(defineProps<{ url: string; password: string; reused?: boolean }>(), { reused: false })
// 部署器页面运行于 http://localhost（安全上下文），navigator.clipboard 可用；
// 异常兜底（如未来经非安全上下文打开）时提示手动选中复制
async function copyPassword() {
  try {
    await navigator.clipboard.writeText(props.password)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选中密码复制')
  }
}
</script>

<template>
  <el-result icon="success" title="部署完成" sub-title="请记好管理员账号密码，忘记只能清数据重部署">
    <template #extra>
      <p>访问地址：{{ url || 'http://localhost' }}　账号：admin</p>
      <p v-if="reused">管理员密码沿用首次部署所设，本次输入未生效</p>
      <p v-else>
        密码：{{ password || '（本次会话未设置，密码为部署时所设）' }}
        <el-button v-if="password" class="copy-password" size="small" @click="copyPassword">复制密码</el-button>
      </p>
      <el-button type="primary" @click="openWeb()">打开网页</el-button>
      <el-button @click="openDashboardWindow()">打开管理窗口</el-button>
    </template>
  </el-result>
</template>
