/** 档案保存后通知详情页刷新完整度（由 PatientDetailLayout 注册）。 */
let refreshHandler: (() => void) | null = null

export function setArchiveCompletenessRefresh(handler: (() => void) | null) {
  refreshHandler = handler
}

export function requestArchiveCompletenessRefresh() {
  refreshHandler?.()
}
