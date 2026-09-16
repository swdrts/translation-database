<script setup lang="ts">
import { openWeb } from '../api/deployer'
const props = defineProps<{ url: string }>()
</script>

<template>
  <el-result icon="success" title="部署完成" sub-title="请记好管理员账号密码，忘记只能清数据重部署">
    <template #extra>
      <p>访问地址：{{ url || 'http://localhost' }}　账号：admin</p>
      <el-button type="primary" @click="openWeb(props.url || 'http://localhost')">打开网页</el-button>
    </template>
  </el-result>
</template>
