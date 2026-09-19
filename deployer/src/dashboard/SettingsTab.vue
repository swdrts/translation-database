<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getToolAutostart, setToolAutostart, changePort, openDataDir, getRegistryMirrors, setRegistryMirrors, probeRegistryMirror } from '../api/deployer'

// port 可选：brief 测试用例 mount 时不传 props，缺省 80 与未部署态一致
const props = withDefaults(defineProps<{ port?: number }>(), { port: 80 })

const autoStart = ref(false)
// 初始值来自持久化配置（DashboardShell 经 getAppState 取回传入）
const port = ref(props.port)
const applying = ref(false)

// 镜像加速（国内镜像仓库）：编辑区 + 每行实时探测状态（'idle'|'checking'|'ok'|'fail'）
const mirrorsPath = ref('')
const mirrorDefaults = ref<string[]>([])
const mirrorsText = ref('')
const mirrorStates = ref<('idle' | 'checking' | 'ok' | 'fail')[]>([])
const savingMirrors = ref(false)

function parsedMirrors(): string[] {
  return mirrorsText.value.split('\n').map((s) => s.trim()).filter((s) => s.length > 0)
}

async function loadMirrors() {
  try {
    const info = await getRegistryMirrors()
    mirrorsPath.value = info.path
    mirrorDefaults.value = info.defaults
    mirrorsText.value = info.mirrors.join('\n')
    mirrorStates.value = info.mirrors.map(() => 'idle')
  } catch (e) {
    ElMessage.error(String(e))
  }
}

function fillDefaults() {
  mirrorsText.value = mirrorDefaults.value.join('\n')
  mirrorStates.value = mirrorDefaults.value.map(() => 'idle')
}

function onMirrorsInput(v: string) {
  mirrorsText.value = v
  mirrorStates.value = parsedMirrors().map(() => 'idle')
}

// 探测每行镜像源存活：/v2/ 返回 401/200 即视为可用（与 docker 客户端握手同逻辑）
async function probeAll() {
  const list = parsedMirrors()
  mirrorStates.value = list.map(() => 'checking')
  for (let i = 0; i < list.length; i++) {
    try {
      const r = await probeRegistryMirror(list[i])
      mirrorStates.value[i] = r.reachable ? 'ok' : 'fail'
    } catch {
      mirrorStates.value[i] = 'fail'
    }
  }
}

async function saveMirrors() {
  savingMirrors.value = true
  try {
    const applied = await setRegistryMirrors(parsedMirrors())
    mirrorsText.value = applied.join('\n')
    mirrorStates.value = applied.map(() => 'idle')
    ElMessage.success('镜像加速已保存并生效')
  } catch (e) {
    ElMessage.error(String(e))
  } finally {
    savingMirrors.value = false
  }
}

onMounted(async () => {
  try {
    autoStart.value = await getToolAutostart()
  } catch (e) {
    ElMessage.error(String(e))
  }
  await loadMirrors()
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
    <el-form-item label="镜像加速(国内)">
      <div style="width: 100%">
        <p style="color: #909399; font-size: 13px; margin: 0 0 8px">
          国内访问 Docker Hub 较慢，这里每行填一个镜像地址，保存后自动重启 Docker 生效（配置文件：{{ mirrorsPath }}）
        </p>
        <el-input
          class="mirrors-input"
          v-model="mirrorsText"
          type="textarea"
          :rows="6"
          placeholder="每行一个，例如 https://docker.1ms.run"
          @update:model-value="onMirrorsInput"
        />
        <div style="margin-top: 8px">
          <el-button class="fill-default-mirrors" plain @click="fillDefaults">填入推荐的国内镜像</el-button>
          <el-button class="probe-mirrors" plain @click="probeAll">检测可用性</el-button>
          <el-button class="save-mirrors" type="primary" :loading="savingMirrors" @click="saveMirrors">保存镜像配置</el-button>
        </div>
        <ul class="mirror-probe-list" style="list-style: none; padding: 0; margin: 8px 0 0">
          <li v-for="(m, i) in parsedMirrors()" :key="m + i" style="font-size: 13px">
            <span v-if="mirrorStates[i] === 'ok'" style="color: #67c23a">✓ {{ m }}</span>
            <span v-else-if="mirrorStates[i] === 'fail'" style="color: #f56c6c">✗ {{ m }}（不可达）</span>
            <span v-else-if="mirrorStates[i] === 'checking'" style="color: #909399">… {{ m }}</span>
            <span v-else style="color: #909399">{{ m }}</span>
          </li>
        </ul>
      </div>
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