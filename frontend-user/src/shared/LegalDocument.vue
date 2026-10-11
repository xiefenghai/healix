<script setup lang="ts">
import { LEGAL_EFFECTIVE_DATE, type LegalDoc } from './legal'

defineProps<{ doc: LegalDoc }>()

/** 拆出 **加粗** 片段；文本均为静态常量，不经 v-html */
function segments(text: string) {
  return text.split(/(\*\*[^*]+\*\*)/).filter(Boolean).map((s) =>
    s.startsWith('**') ? { bold: true, text: s.slice(2, -2) } : { bold: false, text: s },
  )
}
</script>

<template>
  <article class="legal-doc">
    <h1>{{ doc.title }}</h1>
    <p class="date">生效日期：{{ LEGAL_EFFECTIVE_DATE }}</p>
    <p>
      <template v-for="(s, i) in segments(doc.intro)" :key="i">
        <strong v-if="s.bold">{{ s.text }}</strong><template v-else>{{ s.text }}</template>
      </template>
    </p>
    <section v-for="sec in doc.sections" :key="sec.title">
      <h2>{{ sec.title }}</h2>
      <p v-for="(para, pi) in sec.paragraphs" :key="pi">
        <template v-for="(s, i) in segments(para)" :key="i">
          <strong v-if="s.bold">{{ s.text }}</strong><template v-else>{{ s.text }}</template>
        </template>
      </p>
    </section>
  </article>
</template>

<style scoped>
.legal-doc { padding: 16px 18px 40px; font-size: 14px; line-height: 1.75; color: var(--hx-text); }
h1 { margin: 4px 0 4px; font-size: 20px; }
h2 { margin: 20px 0 6px; font-size: 15px; }
p { margin: 0 0 8px; }
.date { color: var(--hx-muted); font-size: 12px; }
strong { font-weight: 600; }
</style>
