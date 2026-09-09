<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  FAMILY_HISTORY_DISEASE_GROUPS,
  KINSHIP_LEVELS,
  KINSHIP_MEMBERS,
  formatFamilyHistoryTag,
  familyDiseaseLabel,
  kinshipLevelLabel,
  memberLabel,
  parseMemberPick,
  toMemberPick,
  type FamilyHistoryEntry,
  type KinshipLevel,
} from '../../shared/family-history-options'

const entries = defineModel<FamilyHistoryEntry[]>('entries', { default: () => [] })
const hasHistory = defineModel<'none' | 'has'>('hasHistory', { default: 'none' })

const dialogVisible = ref(false)
const activeKinshipLevel = ref<KinshipLevel>('1')
const draft = reactive({
  memberPick: '',
  diseases: [] as string[],
  note: '',
})

const selectedMemberText = computed(() => {
  const parsed = parseMemberPick(draft.memberPick)
  if (!parsed) return ''
  return `${kinshipLevelLabel(parsed.kinshipLevel)} · ${memberLabel(parsed.member)}`
})

const selectedDiseasesText = computed(() => {
  if (!draft.diseases.length) return ''
  return draft.diseases.map(familyDiseaseLabel).join('，')
})

const activeLevelMeta = computed(
  () => KINSHIP_LEVELS.find((l) => l.value === activeKinshipLevel.value) ?? KINSHIP_LEVELS[0],
)

function openDialog() {
  draft.memberPick = ''
  draft.diseases = []
  draft.note = ''
  activeKinshipLevel.value = '1'
  dialogVisible.value = true
}

function selectMember(level: KinshipLevel, member: string) {
  draft.memberPick = toMemberPick(level, member)
}

function toggleDisease(code: string) {
  const idx = draft.diseases.indexOf(code)
  if (idx >= 0) {
    draft.diseases.splice(idx, 1)
  } else {
    draft.diseases.push(code)
  }
}

function removeEntry(index: number) {
  entries.value = entries.value.filter((_, i) => i !== index)
}

function confirmDialog() {
  const parsed = parseMemberPick(draft.memberPick)
  if (!parsed) {
    ElMessage.warning('请选择家庭成员')
    return
  }
  if (!draft.diseases.length) {
    ElMessage.warning('请至少选择一项家族病史')
    return
  }
  entries.value = [
    ...entries.value,
    {
      kinshipLevel: parsed.kinshipLevel,
      member: parsed.member,
      diseases: [...draft.diseases],
      note: draft.note.trim() || undefined,
    },
  ]
  hasHistory.value = 'has'
  dialogVisible.value = false
}
</script>

<template>
  <div class="fh-editor">
    <el-radio-group v-model="hasHistory" class="fh-status">
      <el-radio value="none">无家族史</el-radio>
      <el-radio value="has">有家族史</el-radio>
    </el-radio-group>

    <p v-if="hasHistory === 'none' && entries.length" class="fh-unsaved-hint">
      已选「无家族史」，尚未保存；切换回「有家族史」可恢复下方 {{ entries.length }} 条记录
    </p>

    <div v-if="hasHistory === 'has'" class="fh-body">
      <div class="fh-tags">
        <el-tag
          v-for="(entry, index) in entries"
          :key="`${entry.kinshipLevel}-${entry.member}-${index}`"
          closable
          effect="plain"
          class="fh-tag"
          @close="removeEntry(index)"
        >
          {{ formatFamilyHistoryTag(entry) }}
        </el-tag>
        <el-button class="fh-add" circle type="primary" plain @click="openDialog">+</el-button>
      </div>
      <p v-if="!entries.length" class="fh-hint">点击 + 添加家族史条目</p>
    </div>

    <el-dialog v-model="dialogVisible" title="添加家族史" width="760px" destroy-on-close append-to-body>
      <div class="fh-dialog-split">
        <div class="fh-dialog-col">
          <div class="fh-col-title">选择家庭成员</div>
          <el-segmented
            v-model="activeKinshipLevel"
            :options="KINSHIP_LEVELS.map((l) => ({ label: l.label, value: l.value }))"
            block
            class="fh-level-segment"
          />
          <p class="fh-level-hint">{{ activeLevelMeta.hint }}</p>
          <div class="fh-option-grid">
            <button
              v-for="m in KINSHIP_MEMBERS[activeKinshipLevel]"
              :key="m.value"
              type="button"
              class="fh-option-btn"
              :class="{ active: draft.memberPick === toMemberPick(activeKinshipLevel, m.value) }"
              @click="selectMember(activeKinshipLevel, m.value)"
            >
              {{ m.label }}
            </button>
          </div>
          <div v-if="selectedMemberText" class="fh-selected">已选：{{ selectedMemberText }}</div>
        </div>

        <div class="fh-dialog-col fh-dialog-col-disease">
          <div class="fh-col-title">家族病史</div>
          <div v-if="selectedDiseasesText" class="fh-selected fh-selected-top">已选：{{ selectedDiseasesText }}</div>
          <div class="fh-disease-scroll">
            <div v-for="g in FAMILY_HISTORY_DISEASE_GROUPS" :key="g.label" class="fh-disease-group">
              <div class="fh-group-label">{{ g.label }}</div>
              <div class="fh-option-grid">
                <button
                  v-for="d in g.options"
                  :key="d.value"
                  type="button"
                  class="fh-option-btn"
                  :class="{ active: draft.diseases.includes(d.value) }"
                  @click="toggleDisease(d.value)"
                >
                  {{ d.label }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <el-input
        v-model="draft.note"
        type="textarea"
        :rows="2"
        placeholder="备注（选填）"
        maxlength="200"
        show-word-limit
        class="fh-note"
      />

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmDialog">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.fh-editor {
  width: 100%;
}

.fh-status {
  margin-bottom: 12px;
}

.fh-body {
  min-height: 40px;
}

.fh-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.fh-tag {
  max-width: 100%;
  height: auto;
  white-space: normal;
  line-height: 1.45;
  padding-top: 6px;
  padding-bottom: 6px;
}

.fh-tag :deep(.el-tag__content) {
  white-space: normal;
}

.fh-add {
  flex-shrink: 0;
}

.fh-hint {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--admin-muted);
}

.fh-unsaved-hint {
  margin: 0 0 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.45;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
}

.fh-dialog-split {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 16px;
  min-height: 360px;
}

.fh-dialog-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}

.fh-col-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--admin-text);
}

.fh-level-segment {
  margin-bottom: 8px;
}

.fh-level-hint {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.45;
  color: var(--admin-muted);
}

.fh-disease-scroll {
  flex: 1;
  overflow-y: auto;
  max-height: 300px;
  padding-right: 4px;
}

.fh-disease-group {
  margin-bottom: 14px;
}

.fh-disease-group:last-child {
  margin-bottom: 0;
}

.fh-group-label {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text-secondary);
}

.fh-selected {
  margin-top: 10px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.45;
  color: var(--admin-primary-hover);
  background: var(--admin-primary-muted);
  border-radius: 6px;
  word-break: break-word;
}

.fh-selected-top {
  margin-top: 0;
  margin-bottom: 10px;
}

.fh-note {
  margin-top: 14px;
}

.fh-option-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fh-option-btn {
  padding: 6px 14px;
  font-size: 13px;
  color: var(--admin-text-secondary);
  background: #f8fafc;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.fh-option-btn:hover {
  border-color: var(--admin-primary-light);
  color: var(--admin-primary-hover);
}

.fh-option-btn.active {
  color: var(--admin-primary-hover);
  background: var(--admin-primary-muted);
  border-color: var(--admin-primary);
  font-weight: 600;
}
</style>
