<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getToolAutostart, setToolAutostart, changePort, openDataDir } from '../api/deployer'

// port 可选：brief 测试用例 mount 时不传 props，缺省 80 与未部署态一致
const props = withDefaults(defineProps<{ port?: number }>(), { port: 80 })

const autoStart = ref(false)
// 初始值来自持久化配置（DashboardShell 经 getAppState 取回传入）
const port = ref(props.port)
const applying = ref(false)

onMounted(async () => {
  try {
    autoStart.value = await getToolAutostart()
  } catch (e) {
    ElMessage.error(String(e))
  }
})

async function onToggle(v: boolean | string | number) {
  try {
    await setToolAutostart(Boolean(v))
  } catch (e) {
    autoStart.value = !v // 写注册表/登录项失败时回退开关显示，保持与真实状态一致
    ElMessage.error(String(e))
  }
}

async function applyPort() {
  applying.value = true
  try {
    await changePort(port.value)
    ElMessage.success('端口已应用（仅重建了前端容器）')
  } catch (e) {
    ElMessage.error(String(e))
  } finally {
    applying.value = false
  }
}

async function openDir() {
  try {
    await openDataDir()
  } catch (e) {
    ElMessage.error(String(e))
  }
}
</script>

<template>
  <el-form label-width="130px">
    <el-form-item label="开机自启动">
      <el-switch v-model="autoStart" class="tool-autostart" @change="onToggle" />
      <span style="margin-left: 12px; color: #909399; font-size: 13px">开机时自动启动本部署器（托盘常驻）</span>
    </el-form-item>
    <el-form-item label="网页端口">
      <el-input-number v-model="port" class="port-input" :min="1" :max="65535" :step="1" step-strictly />
      <el-button class="apply-port" type="primary" plain :loading="applying" style="margin-left: 8px" @click="applyPort">
        应用端口
      </el-button>
    </el-form-item>
    <el-form-item>
      <el-button class="open-data" @click="openDir">打开数据目录</el-button>
    </el-form-item>
  </el-form>
  <el-alert
    type="info"
    :closable="false"
    title="数据卷说明"
    description="pgdata / esdata 数据卷由 Docker 管理：改端口、重启或升级容器不会丢失数据；如需彻底清除，请使用「维护」页。"
    style="margin-top: 12px"
  />
</template>
