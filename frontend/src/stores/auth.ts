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
