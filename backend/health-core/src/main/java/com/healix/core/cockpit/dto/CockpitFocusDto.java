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
    /** 尚未加入健管组时为 true，驾驶舱引导先分配 */
    private Boolean needsCareTeamAssign;
    /**
     * 标准服务路径当前位置（0-based）：
     * 建档→分组→首诊评估→制定方案→日常跟进→定期报告→阶段总结→结束。
     */
    private Integer serviceStageIndex;
    private String serviceStageCode;
    private String serviceStageLabel;
    /** 全链路步骤状态，供顶栏进度条 */
    private List<ServiceStageStep> serviceStages = new ArrayList<>();
    private Boolean clientLinked;
    private String riskLevel;
    private Integer streakDays;
    private Double planRate7d;
    /**
     * 现行生效方案中的饮水目标（ml）。供助手与右栏对齐方案口径，避免另编 1500–1700 等旧区间。
     */
    private Integer waterTargetMl;
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
    /** 健康档案完整度 0–100（档案字段，非首诊问卷） */
    private Integer archiveCompletenessPercent;
    private Integer archiveFilledCount;
    private Integer archiveTotalCount;
    /** 首诊评估问卷填写完整度 0–100（allDone/allTotal） */
    private Integer intakeCompletenessPercent;
    private Integer intakeFilledCount;
    private Integer intakeTotalCount;
    /** 是否已提交过首诊/补填（含患者初步交卷） */
    private Boolean intakeSubmitted;
    /**
     * 患者已初步交卷、待健管师确认正式评估。
     * 为 true 时顶栏 SOP 仍停在「首诊评估」，不应跳到「制定方案」。
     */
    private Boolean intakeAwaitingStaffConfirm;
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
        /** FOLLOW_UP 时 payload.followupType，如 ONBOARDING */
        private String followupType;
    }

    /** 标准服务流程一步 */
    @Data
    public static class ServiceStageStep {
        private String code;
        private String label;
        /**
         * done=已完成 / current=当前环节 / todo=未开始 /
         * broken=曾完成但被回退（如移出健管组）
         */
        private String status;
        /** 该节点最近一次完成时间；broken 时为历史上完成该节点的时间 */
        private LocalDateTime completedAt;
        /** 补充说明，如「已移出健管组，需重新分组」 */
        private String hint;
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
