import { ref } from 'vue'

/** 基础档案是否有未保存修改（由 PatientArchiveView 注册） */
export const basicArchiveDirty = ref(false)

let confirmLeaveHandler: (() => Promise<boolean>) | null = null

export function setBasicArchiveDirty(dirty: boolean) {
  basicArchiveDirty.value = dirty
}

export function setBasicArchiveLeaveConfirm(handler: (() => Promise<boolean>) | null) {
  confirmLeaveHandler = handler
}

/** 离开患者档案页或切换顶层 Tab 前调用；无未保存修改时直接返回 true */
export async function confirmLeaveBasicArchive(): Promise<boolean> {
  if (!basicArchiveDirty.value || !confirmLeaveHandler) return true
  return confirmLeaveHandler()
}
