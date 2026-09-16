import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StepConfig from './StepConfig.vue'

describe('StepConfig', () => {
  it('密码过短时提交被拦截并提示', async () => {
    const wrapper = mount(StepConfig, { global: { plugins: [ElementPlus] } })
    await wrapper.find('button.submit').trigger('click')
    expect(wrapper.text()).toContain('管理员密码至少 8 位')
  })
  it('合法输入提交时 emit config', async () => {
    const wrapper = mount(StepConfig, { global: { plugins: [ElementPlus] } })
    await wrapper.find('.admin-password input').setValue('password8')
    await wrapper.find('.admin-password2 input').setValue('password8')
    await wrapper.find('button.submit').trigger('click')
    expect(wrapper.emitted('submit')![0][0]).toMatchObject({ port: 80, admin_password: 'password8' })
  })
})
