package com.healix.core.cockpit.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 驾驶舱左栏「今日优先」按人聚合卡片。 */
@Data
public class CockpitPriorityCardDto {
    private String peopleId;
    private String displayName;
    /** MALE / FEMALE / UNKNOWN */
    private String gender;
    private String careTeamId;
    private String careTeamName;
    private Boolean clientLinked;
    /** HIGH / MEDIUM / LOW */
    private String riskLevel;
    private Integer streakDays;
    private String topReason;
    /** 合并后的任务类型码，如 PLAN_NUDGE */
    private List<String> taskTypes = new ArrayList<>();
    /** 合并后的任务类型中文 */
    private List<String> taskTypeLabels = new ArrayList<>();
    /** 最新相关任务开单时间 */
    private LocalDateTime latestTaskAt;
    /** 近 7 日方案完成率（有则返回） */
    private Double planRate7d;
    private List<String> badges = new ArrayList<>();
    private List<String> taskIds = new ArrayList<>();
    /** OPEN 任务数（= taskIds.size） */
    private int openTaskCount;
    /** 超期 OPEN 任务数 */
    private int overdueTaskCount;
    /** 患者身上 OPEN 任务的最高优先级 HIGH/MEDIUM/LOW */
    private String maxTaskPriority;
    /** urgent | watch | mine —— 服务端分桶后再按 tab 过滤 */
    private String bucket;
    /**
     * 排序用打包分：超期数×1e6 + 优先级权重×1e3 + 待办数。
     * 越大越靠前。
     */
    private int urgencyScore;
}
