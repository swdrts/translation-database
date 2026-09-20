<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { dockerProbe, ensureDocker, installWsl2, getRegistryMirrors, setRegistryMirrors } from '../api/deployer'
import type { ProgressEvent } from './types'
const props = defineProps<{ progress: ProgressEvent | null }>()
const emit = defineEmits<{ (e: 'next'): void }>()
const status = ref<'checking' | 'mirrors' | 'ready' | 'working' | 'error'>('checking')
const message = ref('')
const wslError = ref(false)

// 镜像配置步：每行一个地址。已有配置原样回显（尊重用户既有设置），无配置才预填默认国内地址
const mirrorsText = ref('')
const mirrorsPath = ref('')
const applyingMirrors = ref(false)

// 后端 install_docker 阶段的下载进度（downloaded/total 字节）；total=0（无 Content-Length）时只显示已下载量
const downloadPct = computed(() => {
  const d = props.progress?.download
  return d && d.total > 0 ? Math.floor((d.downloaded / d.total) * 100) : null
})
const downloadMb = computed(() => {
  const d = props.progress?.download
  if (!d) return null
  const dl = Math.floor(d.downloaded / 1048576)
  return d.total > 0 ? `${dl} / ${Math.floor(d.total / 1048576)} MB` : `${dl} MB`
})

function parsedMirrors(): string[] {
  return mirrorsText.value.split('\n').map((s) => s.trim()).filter((s) => s.length > 0)
}

// 应用会触发后端写 daemon.json 并重启 Docker 生效（约 30-60 秒），进度文案经
// deploy://progress（stage=mirrors）推送到 progress.message 展示；失败不阻塞，可跳过
async function applyAndNext() {
  applyingMirrors.value = true
  message.value = ''
  try {
    await setRegistryMirrors(parsedMirrors())
    status.value = 'ready'
    emit('next')
  } catch (e) {
    message.value = `镜像配置保存失败：${e}\n可以跳过继续，稍后在管理窗口「设置」页配置`
  } finally {
    applyingMirrors.value = false
  }
}

function skipAndNext() {
  status.value = 'ready'
  emit('next')
}

// 引擎就绪后进入可见的镜像配置步（不自动跳下一步）；读取失败仅清空输入框不阻塞向导
async function loadMirrorsStep() {
  try {
    const info = await getRegistryMirrors()
    mirrorsPath.value = info.path
    mirrorsText.value = (info.mirrors.length > 0 ? info.mirrors : info.defaults).join('\n')
  } catch {
    mirrorsText.value = ''
  }
  status.value = 'mirrors'
}

async function run() {
  status.value = 'checking'
  try {
    const probe = await dockerProbe()
    if (probe.engine_ready) { await loadMirrorsStep(); return }
    status.value = 'working'
    await ensureDocker()
    await loadMirrorsStep()
  } catch (e) {
    status.value = 'error'
    message.value = String(e)
    wslError.value = message.value.includes('WSL2')
  }
}

onMounted(run)

async function fixWsl() {
  await installWsl2()
  message.value = 'WSL2 安装命令已执行，请按系统提示完成并重启电脑，再回到本窗口重试。'
}
</script>

<template>
  <el-result v-if="status === 'ready'" icon="success" title="运行环境已就绪" sub-title="即将进入下一步" />
  <div v-else-if="status === 'mirrors'">
    <el-alert
      type="success"
      :closable="false"
      title="运行环境已就绪"
      description="由于国内访问 Docker 官方仓库很慢，下面为你配置国内镜像仓库以加快下载。已预填推荐的国内地址，你可以按需修改。"
    />
    <el-form label-position="top" style="margin-top: 12px">
      <el-form-item label="国内镜像仓库地址（每行一个，排在前面的优先使用）">
        <el-input v-model="mirrorsText" class="mirror-input" type="textarea" :rows="6" spellcheck="false" />
      </el-form-item>
    </el-form>
    <p v-if="progress?.message" style="color: #909399; font-size: 13px">{{ progress.message }}</p>
    <el-alert v-if="message" type="error" :title="message" :closable="false" style="white-space: pre-line; margin-bottom: 8px" />
    <el-button class="apply-mirrors" type="primary" :loading="applyingMirrors" @click="applyAndNext">应用镜像配置并继续</el-button>
    <el-button class="skip-mirrors" :loading="applyingMirrors" @click="skipAndNext">跳过，直接继续</el-button>
    <p style="color: #909399; font-size: 12px; margin-top: 8px">
      应用时会自动重启 Docker 使配置生效（约 30-60 秒）；以后也可以在管理窗口「设置」页修改（配置文件：{{ mirrorsPath }}）
    </p>
  </div>
  <div v-else-if="status === 'checking'">
    <el-alert type="info" :closable="false" title="翻译数据库需要一个叫 Docker 的运行引擎，正在检查你的电脑是否已有…" />
    <el-progress indeterminate style="margin-top: 12px" />
  </div>
  <div v-else-if="status === 'working'">
    <el-alert type="info" :closable="false" title="正在自动准备运行引擎（Docker）" description="工具会自动为你下载并安装，大约需要 3–10 分钟（视网速）。期间可能弹出系统确认框，请点击「是」或「允许」。请勿关闭电脑或本窗口。" />
    <p v-if="progress?.message" style="color: #909399; font-size: 13px">{{ progress.message }}</p>
    <template v-if="downloadPct !== null">
      <div style="margin-top: 12px">正在下载 Docker Desktop</div>
      <el-progress :percentage="downloadPct" style="margin-top: 12px" />
      <p v-if="downloadMb" style="color: #909399; font-size: 13px; margin-top: 4px">已下载 {{ downloadMb }}</p>
    </template>
    <el-progress v-else indeterminate style="margin-top: 12px" />
  </div>
  <div v-else>
    <el-alert type="error" title="自动准备失败了，别担心，可以重试" :closable="false" />
    <!-- white-space 可继承：让后端失败知识库附带的 \n建议：… 换行展示 -->
    <el-alert type="error" :title="message" :closable="false" style="white-space: pre-line; margin-top: 8px" />
    <el-button v-if="wslError" type="primary" style="margin-top: 12px" @click="fixWsl">一键安装 WSL2</el-button>
    <el-button style="margin-top: 12px" @click="run">重试</el-button>
  </div>
</template>