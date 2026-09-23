/** 智能体 CALL_API 共用执行：催办 / 建随访 / 发布报告 / 发布方案 / 发沟通 / 领任务 */
import { ElMessageBox } from 'element-plus'
import { api } from './http'

export type CallApiPayload = Record<string, unknown> | undefined

export type CallApiResult = {
  message: string
  /** 执行后建议打开的 sheet mode（如 care-plan） */
  openSheet?: string
}

export async function runAgentCallApi(
  apiKey: string,
  peopleId: string,
  payload?: CallApiPayload,
): Promise<CallApiResult> {
  if (apiKey === 'NUDGE') {
    const res = await api<{ data: { sent?: boolean; message?: string; reason?: string } }>(
      `/api/b/v1/adherence/patients/${peopleId}/nudge`,
      { method: 'POST' },
    )
    if (res.data?.reason === 'NO_LINKED_ACCOUNT') {
      return { message: '患者未激活 C 端账号，无法站内提醒' }
    }
    return {
      message: res.data?.message || (res.data?.sent ? '已发送站内提醒' : '提醒未发送'),
    }
  }

  if (apiKey === 'CREATE_FOLLOWUP') {
    const followupType =
      typeof payload?.followupType === 'string' ? payload.followupType : 'ROUTINE'
    const draft =
      typeof payload?.draftContent === 'string' && payload.draftContent.trim()
        ? payload.draftContent.trim()
        : ''
    await api('/api/b/v1/followups', {
      method: 'POST',
      body: JSON.stringify({
        peopleId,
        followupType,
        createTask: payload?.createTask !== false,
        completeNow: false,
        content: draft ? { followupType, guidance: draft } : { followupType },
      }),
    })
    return {
      message: draft
        ? '已创建随访待办（含指导草稿），请在随访页完善并办结'
        : '已创建随访待办，请在随访页完善并办结',
    }
  }

  if (apiKey === 'SEND_CARE_CHAT') {
    const draft =
      typeof payload?.draftContent === 'string' && payload.draftContent.trim()
        ? payload.draftContent.trim()
        : ''
    if (!draft) throw new Error('缺少沟通内容')
    await ElMessageBox.confirm('确认将沟通草稿发送给患者？患者端即时可见。', '确认发送沟通', {
      type: 'warning',
      confirmButtonText: '确认发送',
    })
    await api(`/api/b/v1/patients/${peopleId}/care-chat/messages`, {
      method: 'POST',
      body: JSON.stringify({ content: draft }),
    })
    return { message: '沟通已发送给患者' }
  }

  if (apiKey === 'CLAIM_TASK') {
    const taskId = typeof payload?.taskId === 'string' ? payload.taskId : ''
    if (!taskId) throw new Error('缺少任务 ID')
    await api(`/api/b/v1/workspace/tasks/${taskId}/claim`, { method: 'POST' })
    return { message: '已领取待办' }
  }

  if (apiKey === 'PUBLISH_REPORT') {
    const reportId = typeof payload?.reportId === 'string' ? payload.reportId : ''
    if (!reportId) throw new Error('缺少报告 ID')
    await ElMessageBox.confirm('确认将点评后的管理报告发布给患者？发布后患者端可见。', '确认发布报告', {
      type: 'warning',
      confirmButtonText: '确认发布',
    })
    await api(`/api/b/v1/health-reports/${reportId}/publish`, {
      method: 'POST',
      body: JSON.stringify({
        staffComment: typeof payload?.staffComment === 'string' ? payload.staffComment : null,
        nextFocus: typeof payload?.nextFocus === 'string' ? payload.nextFocus : null,
        quarterAdvice: typeof payload?.quarterAdvice === 'string' ? payload.quarterAdvice : null,
      }),
    })
    return { message: '管理报告已发布给患者' }
  }

  if (apiKey === 'PUBLISH_CARE_PLAN') {
    const bundle = await api<{
      data: {
        draft?: {
          safetyFlags?: Array<{ level?: string; code?: string; message?: string }>
        } | null
      }
    }>(`/api/b/v1/patients/${peopleId}/care-plan`)
    const flags = bundle.data?.draft?.safetyFlags || []
    const errors = flags.filter((f) => (f.level || '').toUpperCase() === 'ERROR')
    if (errors.length) {
      try {
        await ElMessageBox.confirm(
          `存在 ERROR 级安全问题，无法直接发布：\n${errors
            .map((f) => `· ${f.message || f.code}`)
            .join('\n')}\n\n是否打开方案页修正？`,
          '需先修正方案',
          { type: 'error', confirmButtonText: '打开方案页', cancelButtonText: '取消' },
        )
        return { message: '请先在方案页修正安全问题后再发布', openSheet: 'care-plan' }
      } catch {
        throw new Error(
          `存在 ERROR 级安全问题，请先到方案页修正：${errors.map((f) => f.message || f.code).join('；')}`,
        )
      }
    }
    const warns = flags.filter((f) => (f.level || '').toUpperCase() === 'WARN')
    const ack = warns.map((f) => f.code).filter((c): c is string => !!c)
    if (warns.length) {
      await ElMessageBox.confirm(
        `存在警告：\n${warns.map((f) => `· ${f.message || f.code}`).join('\n')}\n\n确认后继续发布？`,
        '确认发布方案',
        { type: 'warning', confirmButtonText: '确认发布' },
      )
    } else {
      await ElMessageBox.confirm('确认将当前方案草稿发布给患者？', '确认发布方案', {
        type: 'warning',
        confirmButtonText: '确认发布',
      })
    }
    await api(`/api/b/v1/patients/${peopleId}/care-plan/publish`, {
      method: 'POST',
      body: JSON.stringify({ ackWarnCodes: ack }),
    })
    return { message: '管理方案已发布' }
  }

  throw new Error('暂不支持该动作')
}

export function callApiDoneLabel(apiKey: string): string | null {
  if (apiKey === 'CREATE_FOLLOWUP') return '已创建随访待办'
  if (apiKey === 'NUDGE') return '已发送提醒'
  if (apiKey === 'SEND_CARE_CHAT') return '已发送沟通'
  if (apiKey === 'CLAIM_TASK') return '已领取待办'
  if (apiKey === 'PUBLISH_REPORT') return '已发布报告'
  if (apiKey === 'PUBLISH_CARE_PLAN') return '已发布方案'
  return null
}
