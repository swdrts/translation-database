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
    // 文件下载（blob）不解包 ApiResponse，直接把 Blob 交给调用方
    if (resp.config?.responseType === 'blob') {
      return resp.data
    }
    const body = resp.data
    if (body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body.data
  },
  async (error) => {
    const status = error.response?.status
    let code = error.response?.data?.code
    let message = error.response?.data?.message
    // blob 请求的业务错误：响应体是 Blob 包着的 JSON，解出来拿消息。
    // 不用 instanceof Blob——jsdom/Node 双 realm 下构造器不同会漏判，按 .text() 鸭子类型识别。
    const data = error.response?.data
    if (data && typeof (data as { text?: unknown }).text === 'function') {
      try {
        const parsed = JSON.parse(await (data as Blob).text())
        code = parsed.code
        message = parsed.message
      } catch {
        /* 解析失败走兜底文案 */
      }
    }
    if (status === 401) {
      localStorage.removeItem('transdb_token')
      localStorage.removeItem('transdb_user')
      import('../stores/auth').then(({ useAuthStore }) => {
        useAuthStore().clear()
      })
      if (code !== 1001 && !location.pathname.startsWith('/login')) {
        location.href = '/login'
      } else {
        // 登录接口的业务失败（1001 凭据错误 / 1004 账号禁用）不跳转，把后端消息展示出来
        ElMessage.error(message || '登录失败')
      }
    } else {
      ElMessage.error(message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default http
