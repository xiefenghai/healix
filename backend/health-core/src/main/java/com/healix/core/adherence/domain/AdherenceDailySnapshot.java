package com.healix.core.adherence.domain;

import com.healix.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 依从性日快照：每天固化一条机构（或健管组）级汇总，用于画趋势。
 *
 * <p>看板的实时口径要遍历全机构患者的方案任务与打卡，天数一多就扛不住；
 * 而历史数据不会再变，落快照后趋势查询退化为一次范围扫描。
 *
 * <p>{@code careTeamId} 为空串表示机构整体（唯一键不允许 NULL 参与去重）。
 */
@Getter
@Setter
public class AdherenceDailySnapshot extends BaseEntity {
    private String tenantId;
    private String orgId;
    /** 健管组业务ID；空串 = 机构整体 */
    private String careTeamId;
    private LocalDate snapshotDate;

    /** 纳入统计的在管患者数 */
    private int universeCount;
    /** 需跟进（高风险）人数 */
    private int followUpCount;
    private int planIncompleteCount;
    private int medIncompleteCount;
    /** 连续未打卡达阈值的人数 */
    private int streakGe3Count;

    private int planDueSum;
    private int planDoneSum;
    private int medDueDoseSum;
    private int medTakenDoseSum;
    /** 方案完成率（0–1），无应打任务时为空 */
    private BigDecimal planRate;
    /** 用药按次达标率（0–1），无应服次数时为空 */
    private BigDecimal medRate;
}
