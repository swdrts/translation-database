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
  <el-result icon="success" title="安装完成" sub-title="翻译数据库已经可以使用了" />
  <el-alert
    type="warning"
    :closable="false"
    title="重要：请务必记下管理员密码"
    description="忘记密码无法找回，只能清空全部数据重新安装。"
    style="margin-bottom: 12px"
  />
  <el-descriptions :column="1" border>
    <el-descriptions-item label="访问地址">
      <el-link type="primary" :underline="false" @click="openWeb()">{{ url || 'http://localhost' }}</el-link>
    </el-descriptions-item>
    <el-descriptions-item label="登录账号">admin</el-descriptions-item>
    <el-descriptions-item label="管理员密码">
      <template v-if="reused">管理员密码沿用首次部署所设，本次输入未生效</template>
      <template v-else>
        {{ password || '（本次会话未设置，密码为部署时所设）' }}
        <el-button v-if="password" class="copy-password" size="small" @click="copyPassword">复制密码</el-button>
      </template>
    </el-descriptions-item>
  </el-descriptions>
  <div style="margin-top: 16px">
    <el-button type="primary" @click="openWeb()">打开网页</el-button>
    <el-button @click="openDashboardWindow()">打开管理窗口</el-button>
  </div>
</template>
