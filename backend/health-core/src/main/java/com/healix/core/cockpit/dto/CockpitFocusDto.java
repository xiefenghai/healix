package com.healix.core.cockpit.dto;

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
    /** 病种档案代码 */
    private List<String> diseaseCodes = new ArrayList<>();
    /** 病种展示名 */
    private List<String> diseaseLabels = new ArrayList<>();
    /** 最近血压文案，如 138/86 mmHg */
    private String bloodPressure;
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
