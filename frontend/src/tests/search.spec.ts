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
