package com.healix.core.workspace.dto;

/**
 * 机构患者列表 KPI：在管 / 未入组 / 关注 / 本月与今日新增。
 */
public record OrgPatientListStats(
        long total, long unassigned, long watched, long monthNew, long todayNew) {}
