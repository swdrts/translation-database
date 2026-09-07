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
      import('../stores/auth').then(({ useAuthStore }) => {
        useAuthStore().clear()
      })
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
