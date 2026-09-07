# 前端与 Docker 部署实施计划 — Plan 4/4

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vue 3 + Element Plus 前端（登录/搜索/详情/编辑/导入/管理后台）+ Docker Compose 一键部署全家桶（frontend/backend/postgres/elasticsearch）+ JWT fail-fast 安全加固。

**Architecture:** 前端 SPA（Vite 构建、Nginx 托管并反代 `/api`）；后端多阶段 Docker 构建；ES 用 Plan 2 的插件镜像；backend 容器等 PG/ES 健康后启动。前端通过统一 `api`（axios 拦截器注入 JWT、401 跳登录）与后端既有 REST 契约交互。

**Tech Stack:** Vue 3.5 + TypeScript + Vite 6 + Element Plus 2.9 + Pinia 2 + Vue Router 4 + Vitest；Node 22 构建；Nginx 1.27；JRE 21 运行镜像。

## Global Constraints

- 前端代码在 `frontend/`；部署文件在仓库根（`docker-compose.yml`、`.env.example`）与 `backend/Dockerfile`、`frontend/Dockerfile`、`frontend/nginx.conf`
- 后端契约冻结（Plan 1-3 收尾备忘）：统一响应 `{code,message,data}`；错误码表（1001/1002/1004/2001/2002/2003/3001-3005/4001/5001-5005/9001-9004）；搜索响应含 `degraded/facets/highlight`；导入两阶段 VO
- 页面路由：`/login`、`/`（搜索首页：联想、标签/朝代/书名筛选、高亮结果、降级横幅、分页）、`/segments/:id`（详情）、`/segments/new`、`/segments/:id/edit`、`/import`（向导：上传→预览→确认→结果）、`/admin/users`、`/admin/tags`、`/admin/reindex`（仅 ADMIN 可见入口）
- 权限矩阵（设计 §6）：VIEWER 只读且仅 PUBLISHED；EDITOR+ 录入/导入；ADMIN 删除/用户/标签删/重建索引；前端按 `/auth/me` 的 role 控制菜单与按钮，后端为最终裁决
- 高亮 `<em>` 片段优先渲染（仅对接口返回的 highlight 片段用 v-html，其余一律文本插值）；`degraded:true` 显示"简化搜索模式"横幅
- JWT fail-fast（Plan 1 遗留准入项）：`TRANSDB_PROFILE=prod` 时使用内置 dev secret 启动即失败；dev 不受影响
- 前端测试：Vitest 组件冒烟；后端既有 102 测试不得回退
- 部署验证标准：`docker compose up -d` 后 `http://localhost` 可登录/搜索/录入；`docker compose down -v` 可干净重来
- 用户已批准本项目全部 git 操作（子代理提交、主控推送）

---

### Task 1: JWT fail-fast + Docker 构建文件与 compose 骨架

**Files:**
- Modify: `backend/src/main/java/com/transdb/security/JwtService.java`（fail-fast）
- Create: `backend/src/test/java/com/transdb/security/JwtFailFastTest.java`
- Create: `backend/Dockerfile`
- Create: `frontend/Dockerfile`（占位 nginx；Task 5 改 Node 多阶段）
- Create: `frontend/nginx.conf`
- Create: `docker-compose.yml`
- Create: `.env.example`
- Modify: `README.md`（一键部署小节）

**Interfaces:**
- Produces: `JwtService` 在 profile=prod 且 secret 为内置 dev 默认值时抛 `IllegalStateException`
- Produces: `backend/Dockerfile`（maven 构建 → temurin-21-jre 运行，非 root，JAVA_OPTS 透传）
- Produces: `docker-compose.yml`（postgres/es healthcheck → backend depends_on healthy → frontend 反代；`TRANSDB_JWT_SECRET/ADMIN_PASSWORD/DB_PASSWORD` 用 `${VAR:?}` 必填校验；数据卷 pgdata/esdata）
- Produces: `nginx.conf`（SPA try_files + `/api` 反代 backend:8080 + `client_max_body_size 55m`）

- [ ] **Step 1: 写失败测试**

`backend/src/test/java/com/transdb/security/JwtFailFastTest.java`：

```java
package com.transdb.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtFailFastTest {

    private static final String DEV_SECRET = "dev-only-secret-key-change-me-32bytes!";

    @Test
    void devSecretRejectedInProdProfile() {
        assertThatThrownBy(() -> new JwtService(DEV_SECRET, 24, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRANSDB_JWT_SECRET");
    }

    @Test
    void strongSecretAcceptedInProdProfile() {
        assertThatCode(() -> new JwtService("0123456789abcdef0123456789abcdef", 24, "prod"))
                .doesNotThrowAnyException();
    }

    @Test
    void devSecretAcceptedOutsideProdProfile() {
        assertThatCode(() -> new JwtService(DEV_SECRET, 24, "dev")).doesNotThrowAnyException();
        assertThatCode(() -> new JwtService(DEV_SECRET, 24, null)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && mvn test -Dtest=JwtFailFastTest`
Expected: 编译失败（三参构造器不存在）

- [ ] **Step 3: 实现**

`JwtService` 改造（保持既有包内 `(secret, Duration)` 构造器不变，`JwtServiceTest` 不受影响）：

```java
    public static final String DEV_DEFAULT_SECRET = "dev-only-secret-key-change-me-32bytes!";

    @Autowired
    public JwtService(@Value("${transdb.jwt.secret}") String secret,
                      @Value("${transdb.jwt.expiry-hours}") long expiryHours,
                      @Value("${TRANSDB_PROFILE:dev}") String profile) {
        this(secret, Duration.ofHours(expiryHours));
        if ("prod".equals(profile) && DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量 TRANSDB_JWT_SECRET 配置强随机密钥（≥32 字节）");
        }
    }

    JwtService(String secret, long expiryHours, String profile) {
        this(secret, Duration.ofHours(expiryHours));
        if ("prod".equals(profile) && DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "生产环境必须通过环境变量 TRANSDB_JWT_SECRET 配置强随机密钥（≥32 字节）");
        }
    }
```

（`@Autowired` 注解与构造器上的 `@Value` 维持既有风格；原两参 Spring 构造器被三参版替代。）

`backend/Dockerfile`：

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd -r -u 1001 transdb
USER transdb
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

`frontend/Dockerfile`（Task 5 改多阶段构建前占位）：

```dockerfile
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
```

`frontend/nginx.conf`：

```nginx
server {
    listen 80;
    server_name _;
    client_max_body_size 55m;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location / {
        root /usr/share/nginx/html;
        try_files $uri $uri/ /index.html;
    }
}
```

`docker-compose.yml`：

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: ${TRANSDB_DB_USERNAME:-transdb}
      POSTGRES_PASSWORD: ${TRANSDB_DB_PASSWORD:?TRANSDB_DB_PASSWORD required}
      POSTGRES_DB: ${TRANSDB_DB_NAME:-transdb}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${TRANSDB_DB_USERNAME:-transdb}"]
      interval: 5s
      timeout: 3s
      retries: 20

  elasticsearch:
    build: ./docker/elasticsearch
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: ${TRANSDB_ES_JAVA_OPTS:--Xms2g -Xmx2g}
    volumes:
      - esdata:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:9200/_cluster/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 60s

  backend:
    build: ./backend
    environment:
      TRANSDB_PROFILE: prod
      TRANSDB_DB_URL: jdbc:postgresql://postgres:5432/${TRANSDB_DB_NAME:-transdb}
      TRANSDB_DB_USERNAME: ${TRANSDB_DB_USERNAME:-transdb}
      TRANSDB_DB_PASSWORD: ${TRANSDB_DB_PASSWORD:?TRANSDB_DB_PASSWORD required}
      TRANSDB_ES_URIS: http://elasticsearch:9200
      TRANSDB_JWT_SECRET: ${TRANSDB_JWT_SECRET:?TRANSDB_JWT_SECRET required}
      TRANSDB_ADMIN_PASSWORD: ${TRANSDB_ADMIN_PASSWORD:?TRANSDB_ADMIN_PASSWORD required}
      JAVA_OPTS: ${TRANSDB_JAVA_OPTS:--Xmx1g}
    depends_on:
      postgres:
        condition: service_healthy
      elasticsearch:
        condition: service_healthy

  frontend:
    build: ./frontend
    ports:
      - "${TRANSDB_FRONTEND_PORT:-80}:80"
    depends_on:
      - backend

volumes:
  pgdata:
  esdata:
```

`.env.example`：

```
# 必填（生产必须改）
TRANSDB_JWT_SECRET=change-me-to-a-random-32-bytes-min-secret
TRANSDB_ADMIN_PASSWORD=change-me-strong-password
TRANSDB_DB_PASSWORD=change-me-db-password

# 可选
# TRANSDB_DB_USERNAME=transdb
# TRANSDB_DB_NAME=transdb
# TRANSDB_FRONTEND_PORT=80
# TRANSDB_ES_JAVA_OPTS=-Xms2g -Xmx2g
# TRANSDB_JAVA_OPTS=-Xmx1g
```

README 在「本地运行后端」小节之后追加：

````markdown
## Docker 一键部署

```bash
cp .env.example .env   # 修改必填三项
docker compose up -d   # 首次构建 ES 插件镜像约需 5-10 分钟
```

- 访问 http://localhost（账号 admin / 你设置的 TRANSDB_ADMIN_PASSWORD）
- 宿主机内存建议 ≥ 8GB（ES 默认堆 2g）
- 纯 PG 开发（不起 ES）时搜索自动降级为简化模式，属正常现象
````

- [ ] **Step 4: 运行测试确认通过 + 全量**

Run: `cd backend && mvn test`
Expected: 103/103 PASS（102 + 3 新测试方法 − 说明：JwtFailFastTest 含 3 个方法，总计以实际为准，全绿即可）

- [ ] **Step 5: 提交**

```bash
git add backend/ frontend/ docker-compose.yml .env.example README.md && git commit -m "feat: JWT fail-fast、后端 Docker 构建与 compose 骨架"
```

---

### Task 2: 前端脚手架（Vite + Vue3 + Element Plus + Pinia + Router）与 API 层

**Files:**
- Create: `frontend/package.json`、`frontend/vite.config.ts`、`frontend/vitest.config.ts`、`frontend/tsconfig.json`、`frontend/index.html`
- Create: `frontend/src/main.ts`、`frontend/src/App.vue`
- Create: `frontend/src/api/http.ts`、`frontend/src/api/index.ts`（全部端点函数与 TS 类型）
- Create: `frontend/src/stores/auth.ts`、`frontend/src/router/index.ts`（路由守卫）
- Create: `frontend/src/components/AppLayout.vue`
- Create: `frontend/src/views/LoginView.vue`（完整）+ 其余 6 个视图**最小占位**（`<template><div>建设中</div></template>`——Vite 动态 import 需要目标文件存在，Task 3/4 替换）
- Create: `frontend/src/tests/login.spec.ts`、`frontend/src/tests/setup.ts`

**Interfaces:**
- Produces: `api/index.ts`（Task 3/4 复用的完整签名）：login/me、listSegments/getSegment/createSegment/updateSegment/deleteSegment、search/suggest/facets、tags CRUD、users 管理、uploadImport/confirmImport、triggerReindex/reindexStatus——统一解包 `{code,message,data}`（code!==0 抛错并 ElMessage.error）
- Produces: `stores/auth.ts`（token+user，localStorage 持久化，`isAdmin/isEditor` getters）
- Produces: 路由守卫（未登录→/login?redirect；登录后访问 /login→/）
- Produces: `http.ts` 拦截器（注入 Authorization；401/1002 清会话跳登录；1001 登录失败不跳转）
- Produces: `AppLayout.vue`（顶栏菜单：首页/导入[EDITOR+]/管理[ADMIN 子菜单：用户/标签/重建索引]，右侧用户名+退出）

- [ ] **Step 1: 实现脚手架**

`frontend/package.json`：

```json
{
  "name": "transdb-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "test": "vitest run",
    "preview": "vite preview"
  },
  "dependencies": {
    "axios": "^1.7.9",
    "element-plus": "^2.9.3",
    "pinia": "^2.3.0",
    "vue": "^3.5.13",
    "vue-router": "^4.5.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1",
    "typescript": "~5.6.3",
    "vite": "^6.0.7",
    "vitest": "^2.1.8",
    "vue-tsc": "^2.2.0"
  }
}
```

`frontend/vite.config.ts`：

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' }
  },
  build: { outDir: 'dist' }
})
```

`frontend/vitest.config.ts`：

```typescript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/tests/setup.ts'],
    globals: true
  }
})
```

`frontend/tsconfig.json`：

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "lib": ["ES2022", "DOM"],
    "types": ["vitest/globals"],
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src/**/*.ts", "src/**/*.vue"]
}
```

`frontend/index.html`：

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>翻译学术数据库</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

`frontend/src/main.ts`：

```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
```

`frontend/src/App.vue`：

```vue
<template>
  <router-view v-if="isLogin" />
  <AppLayout v-else>
    <router-view />
  </AppLayout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from './components/AppLayout.vue'

const route = useRoute()
const isLogin = computed(() => route.path === '/login')
</script>
```

`frontend/src/api/http.ts`：

```typescript
import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api/v1', timeout: 30000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('transdb_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body.data
  },
  (error) => {
    const status = error.response?.status
    const code = error.response?.data?.code
    const message = error.response?.data?.message
    if (status === 401) {
      localStorage.removeItem('transdb_token')
      localStorage.removeItem('transdb_user')
      if (code !== 1001 && !location.pathname.startsWith('/login')) {
        location.href = '/login'
      }
    } else {
      ElMessage.error(message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default http
```

`frontend/src/stores/auth.ts`：

```typescript
import { defineStore } from 'pinia'

export interface AuthUser {
  id: number
  username: string
  displayName: string
  role: 'ADMIN' | 'EDITOR' | 'VIEWER'
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('transdb_token') || '',
    user: JSON.parse(localStorage.getItem('transdb_user') || 'null') as AuthUser | null
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN',
    isEditor: (s) => s.user?.role === 'ADMIN' || s.user?.role === 'EDITOR'
  },
  actions: {
    setSession(token: string, user: AuthUser) {
      this.token = token
      this.user = user
      localStorage.setItem('transdb_token', token)
      localStorage.setItem('transdb_user', JSON.stringify(user))
    },
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem('transdb_token')
      localStorage.removeItem('transdb_user')
    }
  }
})
```

`frontend/src/api/index.ts`：

```typescript
import http from './http'

export interface LoginResult {
  token: string
  user: { id: number; username: string; displayName: string; role: string }
}

export interface SegmentDto {
  sourceText: string
  translatedText: string
  workTitle?: string
  chapter?: string
  author?: string
  dynasty?: string
  translator?: string
  notes?: string
  status?: 'DRAFT' | 'PUBLISHED'
  tagIds?: number[]
  version?: number
}

export interface SegmentVO {
  id: number
  sourceText: string
  translatedText: string
  workTitle?: string
  chapter?: string
  author?: string
  dynasty?: string
  translator?: string
  notes?: string
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  tags: string[]
  createdAt: string
  updatedAt: string
}

export interface PageResponse<T> {
  content: T[]
  total: number
  page: number
  size: number
}

export interface SearchItem {
  id: number
  sourceText: string
  translatedText: string
  workTitle?: string
  chapter?: string
  author?: string
  dynasty?: string
  translator?: string
  tags: string[]
  highlight: Record<string, string[]>
  score: number
}

export interface FacetItem { name: string; count: number }
export interface Facets { tags: FacetItem[]; dynasties: FacetItem[]; works: FacetItem[] }
export interface SearchResult {
  content: SearchItem[]
  total: number
  page: number
  size: number
  degraded: boolean
  facets: Facets
}
export interface SuggestResult { works: string[]; authors: string[]; tags: string[] }
export interface TagVO { id: number; name: string; description?: string }
export interface UserVO { id: number; username: string; displayName?: string; role: string }
export interface LineError { line: number; reason: string }
export interface ImportPreview {
  previewId: string
  strategy: string
  totalRows: number
  willImportRows: number
  overwriteRows: number
  skippedRows: number
  errors: LineError[]
  duplicates: LineError[]
}
export interface ImportResult {
  imported: number
  overwritten: number
  skipped: number
  failed: LineError[]
}
export interface ReindexStatus {
  state: 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED'
  indexed: number
  total: number
  startedAt?: string
  finishedAt?: string
  error?: string
}

export const api = {
  login: (username: string, password: string) =>
    http.post<never, LoginResult>('/auth/login', { username, password }),
  me: () => http.get<never, LoginResult['user']>('/auth/me'),
  listSegments: (params: Record<string, unknown>) =>
    http.get<never, PageResponse<SegmentVO>>('/segments', { params }),
  getSegment: (id: number) => http.get<never, SegmentVO>(`/segments/${id}`),
  createSegment: (dto: SegmentDto) => http.post<never, SegmentVO>('/segments', dto),
  updateSegment: (id: number, dto: SegmentDto) => http.put<never, SegmentVO>(`/segments/${id}`, dto),
  deleteSegment: (id: number) => http.delete(`/segments/${id}`),
  search: (params: Record<string, unknown>) => http.get<never, SearchResult>('/search', { params }),
  suggest: (q: string) => http.get<never, SuggestResult>('/suggest', { params: { q } }),
  facets: () => http.get<never, Facets>('/facets'),
  listTags: () => http.get<never, TagVO[]>('/tags'),
  createTag: (name: string, description?: string) => http.post<never, TagVO>('/tags', { name, description }),
  updateTag: (id: number, name: string, description?: string) => http.put<never, TagVO>(`/tags/${id}`, { name, description }),
  deleteTag: (id: number) => http.delete(`/tags/${id}`),
  listUsers: (page = 0, size = 20) => http.get<never, PageResponse<UserVO>>('/users', { params: { page, size } }),
  createUser: (dto: { username: string; password: string; displayName?: string; role: string }) =>
    http.post<never, UserVO>('/users', dto),
  updateUser: (id: number, dto: { displayName?: string; role?: string; status?: string }) =>
    http.put<never, UserVO>(`/users/${id}`, dto),
  uploadImport: (file: File, duplicateStrategy: string) => {
    const form = new FormData()
    form.append('file', file)
    form.append('duplicateStrategy', duplicateStrategy)
    return http.post<never, ImportPreview>('/segments/import', form)
  },
  confirmImport: (previewId: string) =>
    http.post<never, ImportResult>(`/segments/import/${previewId}/confirm`),
  triggerReindex: () => http.post<never, ReindexStatus>('/admin/reindex'),
  reindexStatus: () => http.get<never, ReindexStatus>('/admin/reindex/status')
}
```

`frontend/src/router/index.ts`：

```typescript
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../views/LoginView.vue') },
    { path: '/', component: () => import('../views/SearchView.vue') },
    { path: '/segments/new', component: () => import('../views/SegmentEditView.vue') },
    { path: '/segments/:id', component: () => import('../views/SegmentDetailView.vue') },
    { path: '/segments/:id/edit', component: () => import('../views/SegmentEditView.vue') },
    { path: '/import', component: () => import('../views/ImportView.vue') },
    { path: '/admin/users', component: () => import('../views/AdminUsersView.vue') },
    { path: '/admin/tags', component: () => import('../views/AdminTagsView.vue') },
    { path: '/admin/reindex', component: () => import('../views/AdminReindexView.vue') }
  ]
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.path !== '/login' && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && auth.isLoggedIn) {
    return { path: '/' }
  }
  if (to.path.startsWith('/admin') && !auth.isAdmin) {
    return { path: '/' }
  }
})

export default router
```

`frontend/src/components/AppLayout.vue`：

```vue
<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand" @click="$router.push('/')">翻译学术数据库</div>
      <el-menu mode="horizontal" :default-active="route.path" router class="menu">
        <el-menu-item index="/">首页</el-menu-item>
        <el-menu-item v-if="auth.isEditor" index="/import">导入</el-menu-item>
        <el-sub-menu v-if="auth.isAdmin" index="admin">
          <template #title>管理</template>
          <el-menu-item index="/admin/users">用户管理</el-menu-item>
          <el-menu-item index="/admin/tags">标签管理</el-menu-item>
          <el-menu-item index="/admin/reindex">索引重建</el-menu-item>
        </el-menu-item>
      </el-menu>
      <div class="user-area">
        <span v-if="auth.user">{{ auth.user.displayName || auth.user.username }}</span>
        <el-button link @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-main><slot /></el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function logout() {
  auth.clear()
  router.push('/login')
}
</script>

<style scoped>
.layout { min-height: 100vh; background: #f7f4ef; }
.header { display: flex; align-items: center; background: #fffdf9; border-bottom: 1px solid #e5ded2; }
.brand { font-weight: 700; color: #3d3d3d; margin-right: 32px; cursor: pointer; }
.menu { flex: 1; border-bottom: none; }
.user-area { display: flex; align-items: center; gap: 12px; color: #3d3d3d; }
</style>
```

`frontend/src/views/LoginView.vue`：

```vue
<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">翻译学术数据库</h2>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" data-test="username" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password data-test="password" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" data-test="submit" @click="submit">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const result = await api.login(form.username, form.password)
    auth.setSession(result.token, result.user as any)
    router.push((route.query.redirect as string) || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f7f4ef; }
.login-card { width: 380px; padding: 12px 8px; }
.login-title { text-align: center; color: #3d3d3d; margin-bottom: 24px; }
</style>
```

其余 6 个视图占位文件（SearchView/SegmentDetailView/SegmentEditView/ImportView/AdminUsersView/AdminTagsView/AdminReindexView）：

```vue
<template><div>建设中</div></template>
```

`frontend/src/tests/setup.ts`：

```typescript
// jsdom 环境占位
```

`frontend/src/tests/login.spec.ts`：

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import LoginView from '../views/LoginView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    login: vi.fn().mockResolvedValue({
      token: 't',
      user: { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN' }
    })
  }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

describe('LoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.mocked(api.login).mockClear()
  })

  it('submits credentials and stores session', async () => {
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()] } })
    await wrapper.find('[data-test="username"] input').setValue('admin')
    await wrapper.find('[data-test="password"] input').setValue('admin123')
    await wrapper.find('[data-test="submit"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(api.login)).toHaveBeenCalledWith('admin', 'admin123')
    expect(localStorage.getItem('transdb_token')).toBe('t')
  })

  it('warns when fields are empty', async () => {
    const wrapper = mount(LoginView, { global: { plugins: [createPinia()] } })
    await wrapper.find('[data-test="submit"]').trigger('click')
    await flushPromises()
    expect(vi.mocked(api.login)).not.toHaveBeenCalled()
  })
})
```

- [ ] **Step 2: 运行前端测试与构建**

Run: `cd frontend && npm install && npm run test && npm run build`
Expected: 2/2 PASS；`vue-tsc` + Vite 构建成功

- [ ] **Step 3: 提交**

```bash
git add frontend/ && git commit -m "feat(frontend): 脚手架、API 层、路由守卫与登录页"
```

---

### Task 3: 搜索首页、条目详情与编辑页

**Files:**
- Replace: `frontend/src/views/SearchView.vue`、`frontend/src/views/SegmentDetailView.vue`、`frontend/src/views/SegmentEditView.vue`
- Create: `frontend/src/components/SearchResultCard.vue`、`frontend/src/components/FilterBar.vue`
- Test: `frontend/src/tests/search.spec.ts`

**Interfaces:**
- Consumes: Task 2 `api.search/suggest/facets/listTags/getSegment/createSegment/updateSegment/deleteSegment`、auth store
- Produces:
  - `SearchView`：大搜索框（300ms 防抖联想下拉：书名/作者/标签三组，点击联想词回填搜索）+ `FilterBar`（facets 动态渲染，tags 多选、dynasty/work 单选）+ 结果卡片（原文在上译文在下；highlight 含 `<em>` 片段时对该字段用 `v-html`，否则纯文本；标签 chips；出处行；点击进详情）+ 降级横幅（degraded:true → el-alert "ES 不可用，当前为简化搜索模式"）+ `el-pagination`（total/page/size=20）+ URL query 同步（q/tags/dynasty/work/page，刷新可复现）
  - `FilterBar`：props facets + v-model 筛选对象；变更 emit
  - `SegmentDetailView`：全字段 + 标签 chips + EDITOR+ 可见编辑按钮 + ADMIN 可见删除（ElMessageBox 确认后 delete → 跳首页）
  - `SegmentEditView`：新建/编辑两用；表单（原文/译文/书名/章节/作者/朝代/译者/备注/状态/标签多选——远程搜索已有标签 + 输入回车即时 createTag 并选中）；编辑模式载入详情，提交带 version；409/2002 → 提示"已被他人修改"并重新载入；保存成功跳详情
  - VIEWER 不可见新建/编辑/删除按钮
- 样式基调：米白底 #f7f4ef、墨色 #3d3d3d、朱砂点缀 #b03a2e；结果卡片白底圆角

- [ ] **Step 1: 实现页面与组件**

`frontend/src/tests/search.spec.ts`：

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SearchView from '../views/SearchView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    search: vi.fn(),
    suggest: vi.fn().mockResolvedValue({ works: ['论语'], authors: [], tags: [] }),
    facets: vi.fn().mockResolvedValue({ tags: [{ name: '儒家', count: 1 }], dynasties: [], works: [] }),
    listTags: vi.fn().mockResolvedValue([])
  }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

const okResult = {
  content: [{
    id: 1, sourceText: '学而时习之', translatedText: 'To learn',
    workTitle: '论语', tags: ['儒家'], highlight: { source_text: ['<em>学而</em>时习之'] }, score: 2.5
  }],
  total: 1, page: 0, size: 20, degraded: false,
  facets: { tags: [{ name: '儒家', count: 1 }], dynasties: [], works: [] }
}

describe('SearchView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('transdb_token', 't')
    localStorage.setItem('transdb_user', JSON.stringify({ id: 1, username: 'u', displayName: 'u', role: 'ADMIN' }))
    vi.mocked(api.search).mockResolvedValue(okResult as any)
  })

  it('renders highlighted results and facets', async () => {
    const wrapper = mount(SearchView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(wrapper.html()).toContain('<em>学而</em>时习之')
    expect(wrapper.text()).toContain('To learn')
    expect(wrapper.text()).toContain('儒家')
  })

  it('shows degraded banner when degraded=true', async () => {
    vi.mocked(api.search).mockResolvedValueOnce({
      content: [], total: 0, page: 0, size: 20, degraded: true,
      facets: { tags: [], dynasties: [], works: [] }
    } as any)
    const wrapper = mount(SearchView, { global: { plugins: [createPinia()] } })
    await flushPromises()
    expect(wrapper.text()).toContain('简化搜索模式')
  })
})
```

- [ ] **Step 2: 运行测试与构建**

Run: `cd frontend && npm run test && npm run build`
Expected: 4/4 PASS；构建成功

- [ ] **Step 3: 提交**

```bash
git add frontend/ && git commit -m "feat(frontend): 搜索首页、条目详情与编辑页"
```

---

### Task 3: 导入向导与管理后台

**Files:**
- Replace: `frontend/src/views/ImportView.vue`、`frontend/src/views/AdminUsersView.vue`、`frontend/src/views/AdminTagsView.vue`、`frontend/src/views/AdminReindexView.vue`
- Test: `frontend/src/tests/import.spec.ts`

**Interfaces:**
- Consumes: Task 2 `api.uploadImport/confirmImport/listUsers/createUser/updateUser/listTags/createTag/updateTag/deleteTag/triggerReindex/reindexStatus`
- Produces:
  - `ImportView` 三步状态机（el-steps，`defineExpose({ preview, step, runPreview, confirm })` 供测试驱动）：①上传（el-upload 手动 + 策略单选 SKIP/OVERWRITE/KEEP + 前端预检扩展名 json/csv/xlsx/xls、≤50MB）②预览（统计卡片 + errors 与 duplicates 两个表格 + 确认/取消）③结果（imported/overwritten/skipped 统计 + failed 表格 + 完成/再导按钮）
  - `AdminUsersView`：表格 + 新建对话框 + 行内改角色/禁用启用（确认框）
  - `AdminTagsView`：表 + 新建/改名对话框 + 删除确认
  - `AdminReindexView`：状态卡片 + 触发确认 + RUNNING 时 5s 轮询

- [ ] **Step 1: 实现页面**

`frontend/src/tests/import.spec.ts`：

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ImportView from '../views/ImportView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    uploadImport: vi.fn(),
    confirmImport: vi.fn().mockResolvedValue({
      imported: 2, overwritten: 0, skipped: 1,
      failed: [{ line: 3, reason: 'source_text 不能为空' }]
    })
  }
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({}) }))

describe('ImportView wizard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('transdb_token', 't')
    localStorage.setItem('transdb_user', JSON.stringify({ id: 1, username: 'e', displayName: 'e', role: 'EDITOR' }))
    vi.mocked(api.uploadImport).mockReset()
  })

  it('preview shows error rows before confirm', async () => {
    vi.mocked(api.uploadImport).mockResolvedValue({
      previewId: 'p1', strategy: 'SKIP', totalRows: 3, willImportRows: 2,
      overwriteRows: 0, skippedRows: 0,
      errors: [{ line: 3, reason: 'source_text 不能为空' }], duplicates: []
    } as any)
    const wrapper = mount(ImportView, { global: { plugins: [createPinia()] } })
    await (wrapper.vm as any).runPreview(new File(['[]'], 'a.json'))
    await flushPromises()
    expect(wrapper.text()).toContain('source_text 不能为空')
    expect(wrapper.text()).toContain('2')
  })

  it('result step lists failed rows after confirm', async () => {
    const wrapper = mount(ImportView, { global: { plugins: [createPinia()] } })
    ;(wrapper.vm as any).preview = { previewId: 'p1', strategy: 'SKIP', totalRows: 3, willImportRows: 2, overwriteRows: 0, skippedRows: 0, errors: [], duplicates: [] }
    ;(wrapper.vm as any).step = 2
    await (wrapper.vm as any).confirm()
    await flushPromises()
    expect(wrapper.text()).toContain('导入成功')
    expect(wrapper.text()).toContain('source_text 不能为空')
  })
})
```

- [ ] **Step 2: 运行测试与构建**

Run: `cd frontend && npm run test && npm run build`
Expected: 6/6 PASS；构建成功

- [ ] **Step 3: 提交**

```bash
git add frontend/ && git commit -m "feat(frontend): 导入向导与管理后台页面"
```

---

### Task 4: 前端 Docker 构建与端到端部署验证

**Files:**
- Modify: `frontend/Dockerfile`（Node 多阶段构建）
- Modify: `frontend/nginx.conf`（如需 gzip 微调）

**Interfaces:**
- Produces: `frontend/Dockerfile`：

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci || npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
```

- Produces: 端到端验证记录（写入报告）：compose 全容器 healthy → 登录拿 token → 录入条目 → ES 可搜（非 degraded）→ SPA index 可访问

- [ ] **Step 1: 本地构建前端确认产物**

Run: `cd frontend && npm run build`
Expected: 构建成功

- [ ] **Step 2: compose 构建与启动**

Run:
```bash
cd ..
docker compose build
export TRANSDB_JWT_SECRET=$(openssl rand -hex 32) TRANSDB_ADMIN_PASSWORD=admin-test-123 TRANSDB_DB_PASSWORD=db-test-123
docker compose up -d
docker compose ps
```
Expected: 四服务 running/healthy（backend Maven 构建首次数分钟；ES 镜像已缓存）

- [ ] **Step 3: 端到端冒烟**

Run:
```bash
TOKEN=$(curl -s -X POST http://localhost/api/v1/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin-test-123"}' | grep -o '"token":"[^"]*' | cut -d'"' -f4)
curl -s http://localhost/api/v1/auth/me -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://localhost/api/v1/segments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"sourceText":"道可道，非常道","translatedText":"The Tao that can be told"}'
sleep 3
curl -s "http://localhost/api/v1/search?q=非常道" -H "Authorization: Bearer $TOKEN"
curl -s http://localhost | head -5
```
Expected: me 返回 ADMIN role；条目创建成功；搜索命中且无 `"degraded":true`；SPA index.html 返回。浏览器完整 UI 冒烟（登录→搜索→录入→导入→管理页）并记录到报告。

- [ ] **Step 4: 清理与提交**

Run: `docker compose down -v`
```bash
git add frontend/Dockerfile frontend/nginx.conf && git commit -m "feat: 前端多阶段 Docker 构建与部署验证"
```

---

### Task 5: 全量回归、README 部署章节与推送

**Files:**
- Modify: `README.md`

**Interfaces:**
- Produces: 里程碑完成

- [ ] **Step 1: 全量回归**

Run: `cd backend && mvn test && cd ../frontend && npm run test && npm run build`
Expected: 后端全绿（103+）；前端 6/6；构建成功

- [ ] **Step 2: README 部署章节完善**

在 Docker 小节补充：

```markdown
### 架构与容器

| 容器 | 说明 |
|---|---|
| frontend | Nginx：托管 Vue SPA + `/api` 反向代理 |
| backend | Spring Boot 3.3（Java 21，多阶段构建，非 root 运行） |
| postgres | PostgreSQL 16（数据卷 pgdata） |
| elasticsearch | ES 8.13 + IK/pinyin 插件（数据卷 esdata，堆内存 .env 可调） |

启动顺序由 healthcheck 保证：PG/ES 就绪 → backend 启动（Flyway 建表、初始化 admin、创建 ES 索引）→ frontend 可访问。

### 安全清单（生产）

- `TRANSDB_JWT_SECRET`：≥32 字节强随机（prod profile 下使用内置默认密钥将拒绝启动）
- `TRANSDB_ADMIN_PASSWORD`：首次启动创建 admin 用
- `TRANSDB_DB_PASSWORD`：数据库密码
- token 过期后前端自动跳转登录页（401 拦截）
```

- [ ] **Step 3: 提交并推送**

```bash
git add README.md && git commit -m "docs: README 部署章节完善"
git push   # 由主控执行
```

---

## Self-Review 记录

1. **规格覆盖（Plan 4 范围）**：§10 前端 7 路由→Task 2/3（两个 Task 3 编号实为 T2/T3/T4 三段）；§6 权限矩阵前端体现→路由守卫 + 菜单/按钮可见性（后端最终裁决）；§12 Docker→Task 1/4；§4.3 降级提示→Task 3 横幅；§9 401 处理→Task 2 拦截器；Plan 1 遗留 JWT fail-fast→Task 1；README→Task 5。
2. **占位符扫描**：无 TBD/TODO；占位视图（Task 2）是授权的中间态（Vite 动态 import 需要目标文件存在，Task 3/4 替换）；login.spec 用 vi.mocked（ESM 安全）。
3. **类型一致性**：`api/index.ts` 与后端 VO 一一对应（SearchResult.degraded/facets、ImportPreview/Result、ReindexStatus 四态、1002 跳登录、1001 不跳转）；auth store role 联合类型与后端枚举一致；AppLayout 菜单与路由表一致。
4. **部署验证**：compose `${VAR:?}` 必填校验是 JWT fail-fast 的第二道防线；端到端冒烟覆盖编排/健康检查/反代/登录/录入/ES 搜索。
5. **风险预留**：npm 首次 install 慢（镜像源自行处理并记录）；backend Docker Maven 构建依赖下载（go-offline 层缓存）；Nginx `client_max_body_size 55m` 已配。
