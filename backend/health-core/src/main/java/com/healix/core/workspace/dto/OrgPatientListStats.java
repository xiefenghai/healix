package com.healix.core.workspace.dto;

/**
 * 机构患者列表 KPI：在管 / 未入组 / 关注 / 本月与今日新增 / C 端已关联 / 环比。
 *
 * @param totalMomPct 在管总数相对上月末的环比变化百分比；上月末为 0 时为空
 * @param lastMonthNew 上月新加入人数（用于「本月新增」同比）
 */
public record OrgPatientListStats(
        long total,
        long unassigned,
        long watched,
        long monthNew,
        long todayNew,
        long clientLinked,
        Double totalMomPct,
        long lastMonthNew) {}
