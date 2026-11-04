<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId } from '../../shared/http'
import { parseSampleMeal, sampleMealPayload } from '../../shared/care-plan-labels'

interface RecipeItem {
  id: string
  name: string
  description?: string
  tags?: string[]
  meal?: Record<string, unknown>
  week?: Record<string, unknown> | null
  energyKcalHint?: number | null
  allergenKeywords?: string[]
  status: string
  gmtModified?: string
}

interface SampleMeal {
  text: string
  swapHint: string
}

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const list = ref<RecipeItem[]>([])
const total = ref(0)
const page = ref(1)
const keyword = ref('')
const status = ref('')

const editVisible = ref(false)
const editId = ref<string | null>(null)
const form = reactive({
  name: '',
  description: '',
  tagsText: '',
  energyKcalHint: undefined as number | undefined,
  status: 'ENABLED',
  breakfast: { text: '', swapHint: '' } as SampleMeal,
  lunch: { text: '', swapHint: '' } as SampleMeal,
  dinner: { text: '', swapHint: '' } as SampleMeal,
  snacks: { text: '', swapHint: '' } as SampleMeal,
})

async function ensureOrg() {
  if (!getCurrentOrgId()) {
    await router.replace('/workspace/orgs')
    return false
  }
  return true
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    const q = new URLSearchParams({ page: String(page.value), size: '20' })
    if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
    if (status.value) q.set('status', status.value)
    const res = await api<{ data: { total: number; items: RecipeItem[] } }>(`/api/b/v1/recipes?${q}`)
    list.value = res.data?.items || []
    total.value = res.data?.total || 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.description = ''
  form.tagsText = ''
  form.energyKcalHint = undefined
  form.status = 'ENABLED'
  form.breakfast = { text: '', swapHint: '' }
  form.lunch = { text: '', swapHint: '' }
  form.dinner = { text: '', swapHint: '' }
  form.snacks = { text: '', swapHint: '' }
}

function openCreate() {
  editId.value = null
  resetForm()
  editVisible.value = true
}

function openEdit(row: RecipeItem) {
  editId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  form.tagsText = (row.tags || []).join('、')
  form.energyKcalHint = row.energyKcalHint ?? undefined
  form.status = row.status || 'ENABLED'
  const m = row.meal || {}
  form.breakfast = parseSampleMeal(m.breakfast)
  form.lunch = parseSampleMeal(m.lunch)
  form.dinner = parseSampleMeal(m.dinner)
  form.snacks = parseSampleMeal(m.snacks)
  editVisible.value = true
}

function mealPayload() {
  return {
    breakfast: sampleMealPayload(form.breakfast),
    lunch: sampleMealPayload(form.lunch),
    dinner: sampleMealPayload(form.dinner),
    snacks: sampleMealPayload(form.snacks),
  }
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!form.lunch.text.trim() && !form.dinner.text.trim()) {
    ElMessage.warning('请至少填写午餐或晚餐示例')
    return
  }
  saving.value = true
  try {
    const body = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      tags: form.tagsText
        .split(/[,，、\s]+/)
        .map((s) => s.trim())
        .filter(Boolean),
      meal: mealPayload(),
      week: null,
      energyKcalHint: form.energyKcalHint || null,
      allergenKeywords: [],
      status: form.status,
    }
    if (editId.value) {
      await api(`/api/b/v1/recipes/${editId.value}`, { method: 'PUT', body: JSON.stringify(body) })
      ElMessage.success('已更新')
    } else {
      await api('/api/b/v1/recipes', { method: 'POST', body: JSON.stringify(body) })
      ElMessage.success('已创建')
    }
    editVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: RecipeItem) {
  try {
    await ElMessageBox.confirm(`删除食谱「${row.name}」？`, '删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/recipes/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="toolbar">
      <el-input v-model="keyword" clearable placeholder="搜索名称" style="width: 220px" @keyup.enter="load" />
      <el-select v-model="status" clearable placeholder="状态" style="width: 120px">
        <el-option label="启用" value="ENABLED" />
        <el-option label="停用" value="DISABLED" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button type="success" @click="openCreate">新建食谱</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="标签" min-width="120">
        <template #default="{ row }">
          <el-tag v-for="t in row.tags || []" :key="t" size="small" class="tag">{{ t }}</el-tag>
          <span v-if="!(row.tags || []).length">—</span>
        </template>
      </el-table-column>
      <el-table-column label="热量提示" width="100">
        <template #default="{ row }">{{ row.energyKcalHint ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="gmtModified" label="更新" width="140" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="20"
        @current-change="load"
      />
    </div>

    <el-dialog
      v-model="editVisible"
      :title="editId ? '编辑食谱' : '新建食谱'"
      width="640px"
      destroy-on-close
    >
      <el-form label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tagsText" placeholder="用顿号或逗号分隔，如 控糖、低盐" />
        </el-form-item>
        <el-form-item label="热量提示">
          <el-input-number v-model="form.energyKcalHint" :min="800" :max="4000" :step="50" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio label="ENABLED">启用</el-radio>
            <el-radio label="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="示例·早">
          <el-input v-model="form.breakfast.text" placeholder="正文" />
          <el-input v-model="form.breakfast.swapHint" class="mt6" placeholder="可替换（选填）" />
        </el-form-item>
        <el-form-item label="示例·午">
          <el-input v-model="form.lunch.text" placeholder="正文" />
          <el-input v-model="form.lunch.swapHint" class="mt6" placeholder="可替换（选填）" />
        </el-form-item>
        <el-form-item label="示例·晚">
          <el-input v-model="form.dinner.text" placeholder="正文" />
          <el-input v-model="form.dinner.swapHint" class="mt6" placeholder="可替换（选填）" />
        </el-form-item>
        <el-form-item label="示例·加餐">
          <el-input v-model="form.snacks.text" placeholder="正文" />
          <el-input v-model="form.snacks.swapHint" class="mt6" placeholder="可替换（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  padding: 4px 0 24px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.tag {
  margin-right: 4px;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.mt6 {
  margin-top: 6px;
}
</style>
