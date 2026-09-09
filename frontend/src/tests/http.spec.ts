import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      request: { use: vi.fn() },
      response: { use: vi.fn() }
    },
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
  return { default: { create: vi.fn(() => instance) } }
})

import axios from 'axios'
import { ElMessage } from 'element-plus'
import http from '../api/http'

// 从 mock 的 axios.create 返回值上取回注册的响应错误拦截器（须在模块求值完成后访问）
function getOnError() {
  const instance = (axios as any).create.mock.results[0].value
  return instance.interceptors.response.use.mock.calls[0][1] as (
    error: unknown
  ) => Promise<unknown>
}

function getOnSuccess() {
  const instance = (axios as any).create.mock.results[0].value
  return instance.interceptors.response.use.mock.calls[0][0] as (resp: unknown) => unknown
}

function unauthorizedError(body: { code: number; message: string }) {
  return {
    response: { status: 401, data: body },
    config: { url: '/auth/login' }
  }
}

describe('http 响应拦截器 401 分支', () => {
  beforeEach(() => {
    // 引用 http，防止 esbuild 把未使用的 import 连同模块求值一并删除
    expect(http).toBeDefined()
    setActivePinia(createPinia())
    localStorage.clear()
    vi.spyOn(ElMessage, 'error').mockClear()
  })

  it('登录失败（code=1001）弹出后端错误消息而不是静默', async () => {
    const err = unauthorizedError({ code: 1001, message: '用户名或密码错误' })
    await expect(getOnError()(err)).rejects.toBe(err)
    expect(ElMessage.error).toHaveBeenCalledWith('用户名或密码错误')
  })

  it('登录页上账号被禁用（code=1004）同样弹出消息', async () => {
    history.pushState({}, '', '/login')
    const err = unauthorizedError({ code: 1004, message: '账号已被禁用' })
    await expect(getOnError()(err)).rejects.toBe(err)
    expect(ElMessage.error).toHaveBeenCalledWith('账号已被禁用')
    history.pushState({}, '', '/')
  })

  it('会话过期（code=1002）走跳转逻辑，不弹业务错误', async () => {
    const err = unauthorizedError({ code: 1002, message: '未认证' })
    await expect(getOnError()(err)).rejects.toBe(err)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})

describe('http 响应拦截器 blob 分支', () => {
  beforeEach(() => {
    // 引用 http，防止 esbuild 把未使用的 import 连同模块求值一并删除
    expect(http).toBeDefined()
    setActivePinia(createPinia())
    localStorage.clear()
    vi.spyOn(ElMessage, 'error').mockClear()
  })

  it('blob 响应不解包 ApiResponse，直接返回 Blob 本体', () => {
    const blob = new Blob(['PK-bytes'])
    expect(getOnSuccess()({ config: { responseType: 'blob' }, data: blob })).toBe(blob)
  })

  it('非 blob 成功响应仍解包 data 字段', () => {
    const resp = { config: {}, data: { code: 0, message: 'ok', data: 42 } }
    expect(getOnSuccess()(resp)).toBe(42)
  })

  it('blob 形态的错误响应能解析出后端 message', async () => {
    // jsdom 的 Blob 没有 .text()（浏览器才有），这里用带 text() 的桩对象等价模拟 blob 响应体
    const blobLike = {
      text: () => Promise.resolve(JSON.stringify({ code: 6001, message: '该书名不存在或没有可导出的内容' }))
    }
    const err = {
      response: { status: 404, data: blobLike }
    }
    await expect(getOnError()(err)).rejects.toBe(err)
    expect(ElMessage.error).toHaveBeenCalledWith('该书名不存在或没有可导出的内容')
  })
})
