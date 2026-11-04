<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { showToast } from 'vant'
import {
  markScienceDismissedToday,
  scienceVideo,
  watchScienceToday,
  type ScienceToday,
} from './science'

const props = defineProps<{
  offer: ScienceToday
}>()

const emit = defineEmits<{
  close: []
}>()

const today = ref<ScienceToday>(props.offer)
const revealed = ref(false)
const playing = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)

const clip = computed(() => today.value.clip)
const videoSrc = computed(() => scienceVideo(clip.value?.code))
const sparks = Array.from({ length: 14 }, (_, i) => i)

onMounted(() => {
  window.setTimeout(() => {
    revealed.value = true
  }, 680)
})

function dismiss() {
  markScienceDismissedToday()
  emit('close')
}

async function play() {
  if (playing.value || !clip.value) return
  playing.value = true
  try {
    today.value = await watchScienceToday()
    await nextTick()
    await videoRef.value?.play()
  } catch (e) {
    playing.value = false
    showToast(e instanceof Error ? e.message : '没能播放')
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="mask" :class="{ playing }" role="dialog" aria-label="今日科普" @click.self="dismiss">
      <div v-if="!playing" class="sparks" aria-hidden="true">
        <i v-for="n in sparks" :key="n" :style="{ '--i': n }" />
      </div>

      <div class="sheet" :class="{ play: playing }">
        <button type="button" class="close" aria-label="关闭" @click="dismiss">×</button>

        <p v-if="!playing" class="done-pill">打卡完成</p>

        <div class="flip" :class="{ revealed, away: playing }">
          <div class="face back">
            <span class="mark">✦</span>
            <strong>今日科普</strong>
            <span class="hint">为你准备好了</span>
          </div>
          <div class="face front">
            <p class="eyebrow">今日科普</p>
            <p class="title">{{ clip?.title || '今日科普' }}</p>
            <p v-if="today.replay" class="replay">这段你看过，今天再复习一次。</p>
          </div>
        </div>

        <template v-if="!playing">
          <p class="disclaimer">{{ today.disclaimer }}</p>
          <button type="button" class="play" :disabled="!revealed" @click="play">
            查看科普
          </button>
        </template>
        <video
          v-else-if="videoSrc"
          ref="videoRef"
          class="video"
          :src="videoSrc"
          playsinline
          controls
        />
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 4000;
  background: rgba(12, 28, 26, 0.55);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  animation: fade 0.28s ease;
}
.mask.playing {
  align-items: center;
  background: rgba(0, 0, 0, 0.88);
  padding: calc(12px + env(safe-area-inset-top)) 16px calc(12px + env(safe-area-inset-bottom));
}
.sheet {
  width: min(100%, 360px);
  background: #fff;
  border-radius: 28px;
  padding: 22px 20px 20px;
  position: relative;
  animation: rise 0.55s cubic-bezier(0.2, 0.9, 0.2, 1.15);
  box-shadow: 0 24px 60px rgba(8, 40, 32, 0.28);
}
.sheet.play {
  width: min(100%, 420px);
  background: transparent;
  box-shadow: none;
  padding: 0;
  animation: none;
}
.done-pill {
  margin: 0 auto 14px;
  width: fit-content;
  padding: 4px 12px;
  border-radius: 999px;
  background: #e8f8f2;
  color: #0b7a66;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.04em;
  animation: pop 0.45s 0.15s cubic-bezier(0.2, 0.9, 0.2, 1.2) both;
}
.flip {
  position: relative;
  height: 168px;
  transform-style: preserve-3d;
  transition: transform 0.7s cubic-bezier(0.4, 0.1, 0.2, 1);
}
.flip.revealed {
  transform: rotateY(180deg);
}
.flip.away {
  display: none;
}
.face {
  position: absolute;
  inset: 0;
  border-radius: 20px;
  backface-visibility: hidden;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 16px;
}
.back {
  background: linear-gradient(160deg, #12b394, #0b7a66);
  color: #fff;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}
.front {
  background: #f4faf8;
  transform: rotateY(180deg);
  text-align: center;
}
.mark {
  width: 54px;
  height: 54px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.18);
  font-size: 26px;
  animation: pulse 1.4s ease-in-out infinite;
}
.back strong {
  font-size: 22px;
}
.hint {
  font-size: 13px;
  opacity: 0.9;
}
.eyebrow {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: #0e9f83;
}
.title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: #14362e;
  line-height: 1.35;
}
.replay,
.disclaimer {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #5f746c;
  text-align: center;
}
.play {
  margin-top: 14px;
  width: 100%;
  height: 46px;
  border: 0;
  border-radius: 14px;
  background: linear-gradient(90deg, #0e9f83, #19c39a);
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  animation: glow 1.6s ease-in-out infinite;
}
.play:disabled {
  opacity: 0.55;
  animation: none;
}
.video {
  width: 100%;
  max-height: calc(100dvh - 96px);
  object-fit: contain;
  border-radius: 16px;
  background: #000;
  display: block;
  animation: fade 0.25s ease;
}
.sheet.play .close {
  position: fixed;
  top: calc(8px + env(safe-area-inset-top));
  right: 12px;
  color: #fff;
}
.close {
  position: absolute;
  top: 10px;
  right: 12px;
  border: 0;
  background: transparent;
  font-size: 24px;
  line-height: 1;
  color: #6b7c76;
  cursor: pointer;
  z-index: 2;
}
.sparks {
  position: absolute;
  left: 50%;
  bottom: 42%;
  width: 0;
  height: 0;
  pointer-events: none;
}
.sparks i {
  position: absolute;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ffe08a;
  animation: spark 0.9s ease-out forwards;
  transform: rotate(calc(var(--i) * 25.7deg)) translateY(0);
}
.sparks i:nth-child(odd) {
  background: #7ddec4;
}
@keyframes fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes rise {
  from { opacity: 0; transform: translateY(48px) scale(0.92); }
  to { opacity: 1; transform: none; }
}
@keyframes pop {
  from { opacity: 0; transform: scale(0.6); }
  to { opacity: 1; transform: none; }
}
@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.08); }
}
@keyframes glow {
  0%, 100% { box-shadow: 0 8px 18px rgba(14, 159, 131, 0.25); }
  50% { box-shadow: 0 10px 26px rgba(14, 159, 131, 0.45); }
}
@keyframes spark {
  to {
    opacity: 0;
    transform: rotate(calc(var(--i) * 25.7deg)) translateY(-120px) scale(0.4);
  }
}
</style>
