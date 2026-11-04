package com.healix.core.assessment.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 评估概览：按病种分组，每组展示当前生效引擎（未确诊看患病风险，确诊后看严重等级分层）。 */
@Getter
@Setter
public class AssessmentOverviewDto {
    private List<DiseaseGroupDto> groups = new ArrayList<>();
    /** 最近一次问卷提交 */
    private QuestionnaireBriefDto lastQuestionnaire;
    /** 发给患者、尚未填写的问卷 */
    private QuestionnaireRequestDto pendingRequest;
    private LocalDateTime evaluatedAt;
    private String disclaimer =
            "本模块结论基于公开临床指南规则生成，属于健康管理辅助信息（患病风险 / 管理分层），"
                    + "不构成诊断或治疗建议，不替代执业医师的专业判断与诊疗决策；"
                    + "请以指南原文及临床实际情况为准，规则版本见各引擎标注。";

    @Getter
    @Setter
    public static class DiseaseGroupDto {
        private String diseaseCode;
        private String diseaseLabel;
        private boolean diagnosed;
        /** 确诊依据：病种档案 / 现有疾病 */
        private String diagnosedReason;
        private boolean hasDiseaseArchive;
        private String engineCode;
        private String engineLabel;
        private String category;
        private String categoryLabel;
        private boolean applicable;
        private String notApplicableReason;
        private AssessmentSnapshotDto latest;
    }

    @Getter
    @Setter
    public static class QuestionnaireBriefDto {
        private String id;
        /** B / C */
        private String portal;
        private LocalDateTime submittedAt;
    }

    @Getter
    @Setter
    public static class QuestionnaireRequestDto {
        private String id;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private LocalDateTime submittedAt;
    }
}
