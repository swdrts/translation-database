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
