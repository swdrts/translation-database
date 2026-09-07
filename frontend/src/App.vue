<template>
  <router-view v-if="isLogin" />
  <AppLayout v-else>
    <router-view />
  </AppLayout>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from './components/AppLayout.vue'

const route = useRoute()
const isLogin = computed(() => route.path === '/login')
</script>
