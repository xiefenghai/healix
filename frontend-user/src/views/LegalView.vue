<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { PRIVACY, TERMS } from '../shared/legal'
import LegalDocument from '../shared/LegalDocument.vue'

const route = useRoute()
const router = useRouter()
const doc = computed(() => (route.params.doc === 'privacy' ? PRIVACY : TERMS))

function back() {
  if (window.history.length > 1) router.back()
  else router.replace('/login')
}
</script>

<template>
  <div class="legal">
    <van-nav-bar :title="doc.title" left-arrow @click-left="back" />
    <LegalDocument :doc="doc" />
  </div>
</template>

<style scoped>
.legal { min-height: 100vh; background: #fff; }
</style>
