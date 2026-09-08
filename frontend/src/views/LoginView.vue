<template>
  <div class="login-page">
    <!-- 左侧：书斋意境板（窄屏隐藏） -->
    <aside class="ink-panel">
      <div class="ink-glyph" aria-hidden="true">譯</div>
      <div class="ink-head">
        <SealStamp :size="52" char="译" />
        <div class="ink-brand">翻译学术数据库</div>
      </div>
      <p class="ink-tagline">古文 · 英译 · 句句对照</p>

      <div class="ink-sample">
        <div class="sample-row">
          <span class="field-badge source">原文</span>
          <p class="sample-source">学而时习之，不亦说乎？</p>
        </div>
        <div class="sample-divider"><span>❖</span></div>
        <div class="sample-row">
          <span class="field-badge target">译文</span>
          <p class="sample-target">Is it not a pleasure to learn and practise what one has learnt?</p>
        </div>
      </div>

      <div class="ink-vertical" aria-hidden="true">温故而知新</div>
    </aside>

    <!-- 右侧：登录表单 -->
    <section class="form-panel">
      <div class="form-wrap rise">
        <SealStamp :size="44" char="译" class="mobile-seal" />
        <h1 class="welcome-title">欢迎使用</h1>
        <p class="welcome-lead">这里是一座「古文 · 英译」对照图书馆。<br />请输入账号和密码进入。</p>

        <el-form :model="form" class="login-form" @keyup.enter="submit">
          <el-form-item>
            <el-input
              v-model="form.username"
              size="large"
              placeholder="请输入用户名"
              data-test="username"
            >
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item>
            <el-input
              v-model="form.password"
              size="large"
              type="password"
              placeholder="请输入密码"
              show-password
              data-test="password"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="loading"
            data-test="submit"
            @click="submit"
          >
            进 入 数 据 库
          </el-button>
        </el-form>

        <p class="login-hint">还没有账号？请联系管理员为您开通。</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { api } from '../api'
import { useAuthStore, type AuthUser } from '../stores/auth'
import SealStamp from '../components/SealStamp.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请先输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const result = await api.login(form.username, form.password)
    auth.setSession(result.token, result.user as AuthUser)
    router.push((route.query.redirect as string) || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  min-height: 100vh;
}

/* ---------- 左侧墨色书斋板 ---------- */
.ink-panel {
  position: relative;
  flex: 0 0 42%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 56px 56px 56px 64px;
  overflow: hidden;
  color: #f2e8d5;
  background:
    radial-gradient(900px 500px at -10% 110%, rgba(168, 67, 60, 0.35) 0%, transparent 55%),
    radial-gradient(700px 420px at 110% -10%, rgba(61, 107, 99, 0.25) 0%, transparent 50%),
    linear-gradient(160deg, #37322a 0%, #262019 100%);
}
/* 背景巨字「譯」：淡淡的朱砂轮廓字压底 */
.ink-glyph {
  position: absolute;
  right: -70px;
  bottom: -110px;
  font-family: var(--font-display);
  font-size: 460px;
  line-height: 1;
  color: transparent;
  -webkit-text-stroke: 2px rgba(168, 67, 60, 0.28);
  pointer-events: none;
  user-select: none;
}
.ink-head { display: flex; align-items: center; gap: 16px; }
.ink-brand {
  font-family: var(--font-display);
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 8px;
}
.ink-tagline {
  margin: 18px 0 0;
  font-size: 14.5px;
  letter-spacing: 5px;
  color: rgba(242, 232, 213, 0.72);
}

/* 示例卡片：让用户登录前就看懂这个库长什么样 */
.ink-sample {
  position: relative;
  margin-top: 44px;
  padding: 26px 28px;
  border-radius: 14px;
  background: rgba(253, 246, 231, 0.06);
  border: 1px solid rgba(253, 246, 231, 0.16);
  backdrop-filter: blur(2px);
}
.sample-row { display: flex; flex-direction: column; gap: 10px; }
.sample-source {
  margin: 0;
  font-family: var(--font-display);
  font-size: 21px;
  letter-spacing: 2px;
  line-height: 1.6;
}
.sample-target {
  margin: 0;
  font-size: 15px;
  line-height: 1.7;
  color: rgba(242, 232, 213, 0.82);
  font-family: var(--font-serif);
}
.sample-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 14px 0;
  color: rgba(168, 67, 60, 0.85);
  font-size: 12px;
}
.sample-divider::before,
.sample-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(253, 246, 231, 0.18);
}
.field-badge.source { color: #e2a49e; background: rgba(168, 67, 60, 0.18); }
.field-badge.target { color: #9ec2bb; background: rgba(61, 107, 99, 0.2); }

/* 竖排装饰字 */
.ink-vertical {
  position: absolute;
  top: 56px;
  right: 52px;
  writing-mode: vertical-rl;
  font-family: var(--font-display);
  font-size: 18px;
  letter-spacing: 14px;
  color: rgba(242, 232, 213, 0.4);
}

/* ---------- 右侧表单区 ---------- */
.form-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}
.form-wrap { width: 100%; max-width: 400px; }
.mobile-seal { display: none; margin-bottom: 18px; }
.welcome-title {
  font-family: var(--font-display);
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 6px;
  color: var(--ink);
  margin: 0 0 12px;
}
.welcome-lead {
  color: var(--ink-3);
  font-size: 15px;
  line-height: 1.9;
  margin: 0 0 30px;
}
.login-form :deep(.el-input__wrapper) { border-radius: 12px; }
.submit-btn {
  width: 100%;
  margin-top: 6px;
  border-radius: 12px;
  letter-spacing: 6px;
  box-shadow: 0 6px 18px rgba(168, 67, 60, 0.3);
}
.login-hint {
  margin-top: 26px;
  text-align: center;
  font-size: 13.5px;
  color: var(--ink-3);
}

@media (max-width: 860px) {
  .ink-panel { display: none; }
  .mobile-seal { display: inline-flex; }
}
</style>
