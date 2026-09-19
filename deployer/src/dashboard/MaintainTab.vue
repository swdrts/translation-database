<script setup lang="ts">
import { h, onMounted, onUnmounted, ref } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { ElCheckbox, ElMessage, ElMessageBox } from 'element-plus'
import { uninstall, upgradeStack } from '../api/deployer'
import type { ProgressEvent } from '../wizard/types'

const upgrading = ref(false)
const uninstalling = ref(false)
const progressText = ref('')
let unlisten: (() => void) | null = null

onMounted(async () => {
  // 升级进度复用部署事件通道，仅升级期间展示（向导窗口不在此频道时也无串扰）
  unlisten = await listen<ProgressEvent>('deploy://progress', (e) => {
    if (upgrading.value) progressText.value = e.payload.message
  })
})
onUnmounted(() => unlisten?.())

async function onUpgrade() {
  upgrading.value = true
  progressText.value = ''
  try {
    // 后端返回实际版本号（inspect 读镜像 label）；旧镜像未打 label 时为 null，回退通用文案
    const version = await upgradeStack()
    ElMessage.success(version ? `已更新到 v${version}` : '升级完成：容器已使用最新镜像重建')
  } catch (e) {
    ElMessage.error(String(e))
  } finally {
    upgrading.value = false
    progressText.value = ''
  }
}

async function onUninstall() {
  // 两步确认之一：先确认「删容器、留数据卷」这个卸载动作本身
  try {
    await ElMessageBox.confirm('将停止并删除容器，数据卷保留。继续？', '卸载确认', {
      type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消',
    })
  } catch {
    return // 用户取消
  }
  // 两步确认之二：数据卷去留。message 用 functional component 包住 checkbox，
  // 让勾选状态变化能触发重渲染（一次性静态 VNode 不会随 ref 更新视图）
  const removeData = ref(false)
  try {
    await ElMessageBox.confirm(
      h(() => h(ElCheckbox, {
        modelValue: removeData.value,
        'onUpdate:modelValue': (v: string | number | boolean) => { removeData.value = Boolean(v) },
      }, () => '同时删除全部数据（不可恢复）')),
      '数据卷处理',
      { type: 'warning', confirmButtonText: '卸载', cancelButtonText: '取消' },
    )
  } catch {
    return // 第二步取消视为放弃整个卸载
  }
  uninstalling.value = true
  try {
    await uninstall(removeData.value)
    ElMessage.success(removeData.value
      ? '已卸载并清空全部数据，重新打开部署器将进入全新向导'
      : '容器已停止并删除，数据卷保留于 Docker（pgdata / esdata）')
  } catch (e) {
    ElMessage.error(String(e))
  } finally {
    uninstalling.value = false
  }
}
</script>

<template>
  <el-form label-width="130px">
    <el-form-item label="版本升级">
      <el-button class="upgrade" type="primary" plain :loading="upgrading" @click="onUpgrade">
        检查并升级
      </el-button>
      <span v-if="upgrading && progressText" class="upgrade-progress" style="margin-left: 12px; color: #909399; font-size: 13px">
        {{ progressText }}
      </span>
    </el-form-item>
  </el-form>
  <el-alert
    type="info"
    :closable="false"
    title="升级说明"
    description="升级会下载最新版本并重新启动服务，你的数据不受影响。"
    style="margin-top: 12px"
  />
  <el-collapse style="margin-top: 16px">
    <el-collapse-item name="danger">
      <template #title>
        <span style="color: #f56c6c; font-weight: bold">危险操作（一般无需使用）</span>
      </template>
      <el-button class="uninstall" type="danger" plain :loading="uninstalling" @click="onUninstall">
        卸载
      </el-button>
      <p style="color: #909399; font-size: 13px">
        卸载会停止并删除全部服务：默认保留数据（重新部署后数据与账号密码不变）；勾选「同时删除全部数据」后将彻底清除且不可恢复。
      </p>
    </el-collapse-item>
  </el-collapse>
</template>
