import ElementPlus from 'element-plus'
import { config } from '@vue/test-utils'

// jsdom 环境下全局注册 Element Plus，使测试中 el-* 组件可解析
config.global.plugins = [ElementPlus]
