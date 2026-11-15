import { ref } from 'vue'

/** 患者详情页同步到壳顶栏的姓名；离开详情时清空 */
export const patientDetailCrumbName = ref<string | null>(null)

export function setPatientDetailCrumb(name: string | null | undefined) {
  const n = String(name || '').trim()
  patientDetailCrumbName.value = n || '患者'
}

export function clearPatientDetailCrumb() {
  patientDetailCrumbName.value = null
}
