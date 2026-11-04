package com.healix.core.cockpit.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 驾驶舱右栏当前患者快照（档案 + 评估 + 依从 + 开放任务）。 */
@Data
public class CockpitFocusDto {
    private String peopleId;
    private String displayName;
    /** MALE / FEMALE / UNKNOWN */
    private String gender;
    /** 出生日期，前端算年龄 */
    private String birthday;
    private String careTeamId;
    private String careTeamName;
    private Boolean clientLinked;
    private String riskLevel;
    private Integer streakDays;
    private Double planRate7d;
    private boolean planIncomplete;
    private boolean medIncomplete;
    private int medDueDoseCount;
    private int medTakenDoseCount;
    /** 近 7 日服药完成率（按次）0~1；无在用药为 null */
    private Double medRate7d;
    /** 截至今天连续服药未完成天数（含今天） */
    private int medMissStreakDays;
    /** 近 7 日有饮食记录的天数 */
    private Integer dietDays7d;
    /** 近 7 日有运动记录的天数 */
    private Integer exerciseDays7d;
    /** 病种档案代码 */
    private List<String> diseaseCodes = new ArrayList<>();
    /** 病种展示名 */
    private List<String> diseaseLabels = new ArrayList<>();
    /** 最近血压文案，如 138/86 mmHg */
    private String bloodPressure;
    /** 最近一次血压测量时间 */
    private LocalDateTime bloodPressureAt;
    /** 最近血压 ≥140/90 */
    private boolean bloodPressureHigh;
    /** 过敏史：有过敏时列名称，否则「无已知过敏 / 未询问」 */
    private String allergyText;
    /** 近 7 天饮食 / 运动记录摘要（助手上下文用） */
    private String lifestyleSummary;
    /** 档案完整度 0–100 */
    private Integer archiveCompletenessPercent;
    private Integer archiveFilledCount;
    private Integer archiveTotalCount;
    /** 评估标签（血糖分标 / 发病风险等） */
    private List<AssessmentTag> assessmentTags = new ArrayList<>();
    private List<OpenTaskBrief> openTasks = new ArrayList<>();
    /** 待确认 AI 产物：方案草稿 / 报告草稿等 */
    private List<PendingDraft> pendingDrafts = new ArrayList<>();
    private String archivePath;
    /** 患者沟通入口 */
    private String careChatPath;

    @Data
    public static class AssessmentTag {
        private String engineCode;
        private String diseaseCode;
        /** RISK / SEVERITY */
        private String category;
        /** LOW / MID / HIGH / VERY_HIGH */
        private String tier;
        private String text;
        /** danger / warning / success / info / muted */
        private String tone;
        private String title;
    }

    @Data
    public static class OpenTaskBrief {
        private String id;
        private String taskType;
        private String taskTypeLabel;
        private String summary;
        private String priority;
        private boolean overdue;
        private LocalDateTime dueAt;
    }

    /** 右栏「待你确认」草稿项 */
    @Data
    public static class PendingDraft {
        /** CARE_PLAN / REPORT */
        private String kind;
        private String id;
        private String title;
        private String summary;
        /** 打开抽屉 mode 或相对路径提示 */
        private String sheetMode;
        /** 按钮文案，如「去审阅」 */
        private String actionLabel;
    }
}
