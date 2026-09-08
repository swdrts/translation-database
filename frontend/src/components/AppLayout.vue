<template>
  <div class="layout">
    <header class="topbar">
      <div class="topbar-inner">
        <button class="brand" type="button" @click="$router.push('/')">
          <SealStamp :size="38" char="译" />
          <span class="brand-text">
            <span class="brand-name">翻译学术数据库</span>
            <span class="brand-sub">古文 · 英译 · 一搜即得</span>
          </span>
        </button>

        <nav class="main-nav" aria-label="主导航">
          <button
            class="nav-item"
            :class="{ active: route.path === '/' }"
            type="button"
            @click="$router.push('/')"
          >
            <el-icon><Search /></el-icon>
            <span>找一找</span>
          </button>
          <button
            v-if="auth.isEditor"
            class="nav-item"
            :class="{ active: route.path === '/import' }"
            type="button"
            @click="$router.push('/import')"
          >
            <el-icon><UploadFilled /></el-icon>
            <span>录入资料</span>
          </button>
          <el-dropdown v-if="auth.isAdmin" trigger="click" @command="onAdminNav">
            <button
              class="nav-item"
              :class="{ active: route.path.startsWith('/admin') }"
              type="button"
            >
              <el-icon><Setting /></el-icon>
              <span>系统管理</span>
              <el-icon class="caret"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="/admin/users">用户账号</el-dropdown-item>
                <el-dropdown-item command="/admin/tags">标签分类</el-dropdown-item>
                <el-dropdown-item command="/admin/reindex">搜索修复</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </nav>

        <div class="user-area">
          <span class="user-avatar">{{ avatarChar }}</span>
          <span class="user-meta">
            <span class="user-name">{{ auth.user?.displayName || auth.user?.username }}</span>
            <span class="user-role">{{ roleLabel }}</span>
          </span>
          <el-button link class="logout-btn" @click="logout">退出</el-button>
        </div>
      </div>
    </header>

    <main class="page-main">
      <slot />
    </main>

    <footer class="page-foot">翻译学术数据库 · 句句对照，温故知新</footer>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Search, Setting, UploadFilled } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import SealStamp from './SealStamp.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const avatarChar = computed(() => (auth.user?.displayName || auth.user?.username || '?').slice(0, 1))
const roleLabel = computed(
  () => ({ ADMIN: '管理员', EDITOR: '编辑者', VIEWER: '查看者' })[auth.user?.role || 'VIEWER']
)

function onAdminNav(path: string) {
  router.push(path)
}

function logout() {
  auth.clear()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* ---------- 顶栏 ---------- */
.topbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 253, 247, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--card-edge);
}
.topbar-inner {
  max-width: 1180px;
  margin: 0 auto;
  padding: 10px 24px;
  display: flex;
  align-items: center;
  gap: 28px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  background: none;
  border: none;
  padding: 4px 6px;
  cursor: pointer;
  border-radius: 12px;
  transition: background 0.2s;
}
.brand:hover { background: rgba(168, 67, 60, 0.06); }
.brand-text { display: flex; flex-direction: column; align-items: flex-start; line-height: 1.25; }
.brand-name {
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 19px;
  letter-spacing: 3px;
  color: var(--ink);
}
.brand-sub { font-size: 11.5px; letter-spacing: 2px; color: var(--ink-3); }

/* ---------- 主导航：大点击区域、大白话命名 ---------- */
.main-nav { display: flex; align-items: center; gap: 6px; flex: 1; }
.nav-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-ui);
  font-size: 15.5px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--ink-2);
  background: none;
  border: none;
  padding: 10px 16px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.nav-item .el-icon { font-size: 17px; }
.nav-item:hover { background: rgba(168, 67, 60, 0.07); color: var(--cinnabar); }
.nav-item.active {
  background: var(--cinnabar);
  color: #fdf4e3;
  box-shadow: 0 3px 10px rgba(168, 67, 60, 0.35);
}
.nav-item .caret { font-size: 12px; margin-left: -2px; }

/* ---------- 用户区 ---------- */
.user-area { display: flex; align-items: center; gap: 10px; }
.user-avatar {
  width: 36px;
  height: 36px;
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: linear-gradient(145deg, #e9dcc0, #d9c69c);
  color: #6b5730;
  font-family: var(--font-display);
  font-weight: 700;
  font-size: 17px;
}
.user-meta { display: flex; flex-direction: column; line-height: 1.3; }
.user-name { font-size: 14px; font-weight: 600; color: var(--ink); }
.user-role { font-size: 11.5px; color: var(--ink-3); letter-spacing: 1px; }
.logout-btn { font-family: var(--font-ui); color: var(--ink-3); margin-left: 4px; }
.logout-btn:hover { color: var(--cinnabar); }

/* ---------- 主体与页脚 ---------- */
.page-main {
  flex: 1;
  width: 100%;
  max-width: 1180px;
  margin: 0 auto;
  padding: 28px 24px 48px;
  box-sizing: border-box;
}
.page-foot {
  text-align: center;
  padding: 18px 0 26px;
  font-size: 12.5px;
  letter-spacing: 3px;
  color: var(--ink-3);
  opacity: 0.75;
}

@media (max-width: 760px) {
  .topbar-inner { flex-wrap: wrap; gap: 10px; }
  .brand-sub { display: none; }
  .user-meta { display: none; }
}
</style>
