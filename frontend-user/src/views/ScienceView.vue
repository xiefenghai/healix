<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import {
  loadScienceOverview,
  openScienceToday,
  sciencePoster,
  scienceVideo,
  watchScienceToday,
  type ScienceClip,
  type ScienceOverview,
} from '../shared/science'

const router = useRouter()
const loading = ref(true)
const overview = ref<ScienceOverview | null>(null)
const playing = ref<ScienceClip | null>(null)
const opening = ref(false)

const pct = computed(() => {
  const o = overview.value
  if (!o?.totalCount) return 0
  return Math.round((o.watchedCount / o.totalCount) * 100)
})

async function refresh() {
  overview.value = await loadScienceOverview()
}

onMounted(async () => {
  try {
    await refresh()
  } finally {
    loading.value = false
  }
})

async function startToday() {
  if (opening.value || !overview.value) return
  opening.value = true
  try {
    if (overview.value.canOpen) {
      await openScienceToday()
      await refresh()
    }
    const clip = overview.value?.today
    if (!clip) return
    await watchScienceToday()
    playing.value = clip
    await refresh()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '今天的科普还没准备好')
  } finally {
    opening.value = false
  }
}

async function playWatched(clip: ScienceClip) {
  if (overview.value?.today?.code === clip.code) {
    try {
      await watchScienceToday()
      await refresh()
    } catch {
      /* 今天这条已经记过 */
    }
  } else if (!clip.watched) {
    return
  }
  playing.value = clip
}

function dateLabel(d?: string | null) {
  return d ? String(d).slice(0, 10).replace(/-/g, '.') : ''
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="科普" left-arrow :border="false" @click-left="router.back()" />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>
    <van-empty v-else-if="!overview?.enabled" description="科普暂未开启" image-size="72" />

    <template v-else>
      <section class="hero">
        <p class="headline">今日科普</p>
        <p v-if="overview.today" class="title">{{ overview.today.title }}</p>
        <p v-else-if="overview.holdText" class="hold">{{ overview.holdText }}</p>
        <p v-if="overview.replay && overview.today" class="hold">这段你看过，今天再复习一次。</p>
        <p class="progress">已看 {{ overview.watchedCount }} / {{ overview.totalCount }}</p>
        <div class="bar"><i :style="{ width: pct + '%' }" /></div>
        <p class="disclaimer">{{ overview.disclaimer }}</p>
        <button
          v-if="overview.canOpen || (overview.today && !overview.today.watched)"
          type="button"
          class="play"
          :disabled="opening"
          @click="startToday"
        >
          {{ opening ? '准备中…' : '播放' }}
        </button>
        <button
          v-else-if="overview.today"
          type="button"
          class="play ghost"
          @click="playWatched(overview.today)"
        >
          再看一遍
        </button>
      </section>

      <section class="grid">
        <button
          v-for="clip in overview.clips"
          :key="clip.code"
          type="button"
          class="tile"
          :class="{ later: !clip.watched }"
          :disabled="!clip.watched"
          @click="playWatched(clip)"
        >
          <video
            v-if="clip.watched && sciencePoster(clip.code)"
            class="thumb"
            :src="sciencePoster(clip.code)!"
            preload="metadata"
            muted
            playsinline
          />
          <div v-else class="thumb later-face">待看</div>
          <strong>{{ clip.title }}</strong>
          <span v-if="clip.watched">{{ dateLabel(clip.watchedBizDate) }} 看过</span>
          <span v-else>完成待办后按顺序观看</span>
        </button>
      </section>
    </template>

    <Teleport to="body">
      <div v-if="playing" class="player-mask" @click.self="playing = null">
        <button type="button" class="x" aria-label="关闭" @click="playing = null">×</button>
        <video class="player-video" :src="scienceVideo(playing.code)!" autoplay playsinline controls />
        <p>{{ playing.title }}</p>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg, #f3f7f5);
  padding-bottom: 32px;
}
.loading {
  margin-top: 80px;
}
.hero,
.tile {
  background: #fff;
  border-radius: 20px;
  box-shadow: 0 4px 16px rgba(14, 60, 48, 0.06);
}
.hero {
  margin: 12px 16px;
  padding: 18px 16px;
}
.headline {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: #0e9f83;
}
.title,
.headline + .hold {
  margin: 6px 0 0;
}
.title {
  font-size: 20px;
  font-weight: 800;
  color: #14362e;
}
.hold,
.progress,
.disclaimer {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: #5f746c;
}
.bar {
  margin-top: 12px;
  height: 8px;
  border-radius: 999px;
  background: #e6efeb;
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #0e9f83, #3ecf9a);
}
.play {
  margin-top: 14px;
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 14px;
  background: #0e9f83;
  color: #fff;
  font-size: 15px;
  font-weight: 700;
}
.play.ghost {
  background: #fff;
  color: #0e9f83;
  border: 1px solid #0e9f83;
}
.play:disabled {
  opacity: 0.6;
}
.grid {
  margin: 0 16px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.tile {
  border: 0;
  padding: 8px 8px 12px;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tile.later {
  cursor: default;
}
.thumb {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  border-radius: 12px;
  background: #e6efeb;
  display: block;
  pointer-events: none;
}
.later-face {
  display: grid;
  place-items: center;
  color: #6b7c76;
  font-size: 13px;
}
.tile strong {
  margin-top: 4px;
  font-size: 14px;
  color: #14362e;
}
.tile span {
  font-size: 12px;
  color: #6b7c76;
}
.player-mask {
  position: fixed;
  inset: 0;
  z-index: 4000;
  background: rgba(0, 0, 0, 0.88);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: calc(48px + env(safe-area-inset-top)) 16px calc(16px + env(safe-area-inset-bottom));
}
.x {
  position: fixed;
  top: calc(8px + env(safe-area-inset-top));
  right: 12px;
  border: 0;
  background: transparent;
  color: #fff;
  font-size: 28px;
  line-height: 1;
  cursor: pointer;
  z-index: 1;
}
.player-video {
  width: min(100%, 420px);
  max-height: calc(100dvh - 120px);
  object-fit: contain;
  border-radius: 14px;
  background: #000;
  display: block;
}
.player-mask p {
  margin: 0;
  color: #fff;
  text-align: center;
  font-size: 15px;
  font-weight: 700;
}
</style>
