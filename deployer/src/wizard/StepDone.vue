<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { openWeb, openDashboardWindow } from '../api/deployer'
const props = defineProps<{ url: string; password: string }>()
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
      <p>
        密码：{{ password || '（本次会话未设置，密码为部署时所设）' }}
        <el-button v-if="password" class="copy-password" size="small" @click="copyPassword">复制密码</el-button>
      </p>
      <el-button type="primary" @click="openWeb()">打开网页</el-button>
      <el-button @click="openDashboardWindow()">打开管理窗口</el-button>
    </template>
  </el-result>
</template>
