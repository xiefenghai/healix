package com.healix.core.adherence.dto;

import java.time.LocalDate;
import lombok.Data;

@Data
public class AdherenceBoardEscalateRequest {
    /** 看板统计日，写入随访摘要上下文 */
    private LocalDate date;
    private Boolean planIncomplete;
    private Boolean medIncomplete;
    private Integer streakDays;
    /** 默认 true：开 OPEN 定期随访 + FOLLOW_UP */
    private Boolean openFollowup;
    /**
     * 开打卡跟进（PLAN_NUDGE）。默认：streakDays &gt;= 3 时为 true，否则 false。
     * 显式传 true 也可在 streak&lt;3 时开单（看板人工判断）。
     */
    private Boolean openPlanNudge;
}
