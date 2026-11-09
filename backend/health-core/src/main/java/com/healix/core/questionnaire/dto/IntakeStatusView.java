package com.healix.core.questionnaire.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 客户页首诊卡片：是否已提交、相对最新版待补题数。 */
@Getter
@Setter
public class IntakeStatusView {
    private boolean hasSubmitted;
    /** 最近一次已提交首诊/补填作答 ID，供只读详情 */
    private String latestSubmittedId;
    private String submittedAt;
    private String questionnaireCode;
    private Integer submittedVersion;
    private int latestVersion;
    private int pendingCount;
    private List<PendingField> pendingFields = new ArrayList<>();
    /** C 端最近提交档位：MINIMAL / QUICK / FULL；B 端代填可为空 */
    private String submittedDepth;
    private String submittedDepthLabel;
    /** 问卷填写完整度（allDone/allTotal），与健康档案完整度分开 */
    private Integer completenessPercent;
    private Integer filledCount;
    private Integer totalCount;
    /**
     * 患者已初步交卷、待健管师沟通确认后正式提交。
     * 为 true 时 B 端应打开可编辑草稿做「确认并提交评估」，而不是直接当正式办结。
     */
    private boolean awaitingStaffConfirm;
    /** 待确认时对应的患者已交卷 ID（只读对照用） */
    private String awaitingConfirmResponseId;

    @Getter
    @Setter
    public static class PendingField {
        private String field;
        private String label;
        private String level;
        private String moduleCode;
        private String moduleTitle;
    }
}
