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
