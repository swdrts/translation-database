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
    // Element Plus 将透传属性落在内部 <input> 上，故直接选中 input 元素
    await wrapper.find('input[data-test="username"]').setValue('admin')
    await wrapper.find('input[data-test="password"]').setValue('admin123')
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
