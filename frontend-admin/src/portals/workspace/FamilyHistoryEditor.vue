<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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

const entries = defineModel<FamilyHistoryEntry[]>('entries', { default: [] })
const hasHistory = defineModel<'none' | 'has'>('hasHistory', { default: 'none' })

const dialogVisible = ref(false)
/** 手风琴当前展开的亲属级别，默认全部折叠 */
const expandedKinship = ref<string>('')
/** 手风琴当前展开的疾病分类，默认全部折叠 */
const expandedDiseaseGroup = ref<string>('')
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

watch(hasHistory, (val) => {
  if (val === 'none') {
    entries.value = []
  }
})

watch(
  entries,
  (list) => {
    if (list.length > 0) {
      hasHistory.value = 'has'
    }
  },
  { deep: true },
)

function openDialog() {
  draft.memberPick = ''
  draft.diseases = []
  draft.note = ''
  expandedKinship.value = ''
  expandedDiseaseGroup.value = ''
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
  if (entries.value.length === 0 && hasHistory.value === 'has') {
    // 保持「有家族史」状态，允许继续添加
  }
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

    <el-dialog v-model="dialogVisible" title="家族史" width="520px" destroy-on-close append-to-body>
      <el-form label-width="0" class="fh-form">
        <el-form-item>
          <div class="fh-member-panel">
            <div v-if="selectedMemberText" class="fh-picker-current">已选：{{ selectedMemberText }}</div>
            <div v-else class="fh-picker-placeholder">请选择家庭成员</div>
            <el-collapse v-model="expandedKinship" accordion class="fh-picker-collapse">
              <el-collapse-item v-for="level in KINSHIP_LEVELS" :key="level.value" :name="level.value">
                <template #title>
                  <span class="fh-picker-title">{{ level.label }}</span>
                </template>
                <div class="fh-option-grid">
                  <button
                    v-for="m in KINSHIP_MEMBERS[level.value]"
                    :key="m.value"
                    type="button"
                    class="fh-option-btn"
                    :class="{ active: draft.memberPick === toMemberPick(level.value, m.value) }"
                    @click="selectMember(level.value, m.value)"
                  >
                    {{ m.label }}
                  </button>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-form-item>
        <el-form-item>
          <div class="fh-disease-panel">
            <div v-if="selectedDiseasesText" class="fh-picker-current">已选：{{ selectedDiseasesText }}</div>
            <div v-else class="fh-picker-placeholder">请选择家族病史</div>
            <el-collapse v-model="expandedDiseaseGroup" accordion class="fh-picker-collapse">
              <el-collapse-item
                v-for="g in FAMILY_HISTORY_DISEASE_GROUPS"
                :key="g.label"
                :name="g.label"
              >
                <template #title>
                  <span class="fh-picker-title">{{ g.label }}</span>
                </template>
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
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-form-item>
        <el-form-item>
          <el-input v-model="draft.note" type="textarea" :rows="2" placeholder="备注" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
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

.fh-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.fh-member-panel,
.fh-disease-panel {
  width: 100%;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.fh-picker-current {
  padding: 10px 14px;
  font-size: 13px;
  color: var(--admin-primary-hover);
  background: var(--admin-primary-muted);
  border-bottom: 1px solid var(--admin-border);
  font-weight: 500;
  line-height: 1.5;
  word-break: break-word;
}

.fh-picker-placeholder {
  padding: 10px 14px;
  font-size: 13px;
  color: var(--admin-muted);
  border-bottom: 1px solid var(--admin-border);
}

.fh-picker-collapse {
  border: none;
}

.fh-picker-collapse :deep(.el-collapse-item__header) {
  padding: 0 14px;
  height: 44px;
  font-size: 14px;
  border-bottom: 1px solid var(--admin-border);
}

.fh-picker-collapse :deep(.el-collapse-item:last-child .el-collapse-item__header) {
  border-bottom: none;
}

.fh-picker-collapse :deep(.el-collapse-item__wrap) {
  border-bottom: 1px solid var(--admin-border);
}

.fh-picker-collapse :deep(.el-collapse-item__content) {
  padding: 12px 14px 14px;
}

.fh-picker-title {
  font-weight: 600;
  color: var(--admin-text);
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
