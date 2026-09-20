package com.healix.core.assessment.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssessmentOverviewDto {
    private List<AssessmentSnapshotDto> latest = new ArrayList<>();
    private List<AvailableEngineDto> availableEngines = new ArrayList<>();
    /** 有糖尿病病种档案时提示（CDRS 隐藏原因） */
    private String cdrsHiddenReason;
    /** 病种/适用性等提示条 */
    private List<String> notices = new ArrayList<>();
    /** China-PAR：采集就绪度 + 官方工具录入入口（本地无系数，不自动算分） */
    private ChinaParPrepDto chinaPar;
    /**
     * 固定免责（合规）：非诊断、非治疗决策、不替代执业医师；基于公开指南规则供健管参考。
     */
    private String disclaimer =
            "本模块结论基于公开临床指南规则生成，属于健康管理辅助信息（疾病风险等级 / 疾病分层），"
                    + "不构成诊断或治疗建议，不替代执业医师的专业判断与诊疗决策；"
                    + "请以指南原文及临床实际情况为准，规则版本见各引擎标注。";

    @Getter
    @Setter
    public static class AvailableEngineDto {
        private String engineCode;
        private String engineLabel;
        private String kind;
        private String kindLabel;
        private String rulePackVersion;
        private String guidelineName;
    }

    @Getter
    @Setter
    public static class ChinaParPrepDto {
        private boolean applicable;
        private String notApplicableReason;
        private String officialToolUrl = "https://www.cvdrisk.com.cn/ASCVD/Eval";
        private String rulePackVersion = "CHINA_PAR-RECORD.1";
        private String guidelineName = "中国心血管病风险评估和管理指南（China-PAR）";
        private String kindLabel = "疾病风险等级评估";
        private String note =
                "本地暂未内置 China-PAR 回归系数；请用官方工具算出 10 年/终生风险后在此录入以便留痕。"
                        + "录入结果仅供健康管理参考，不构成诊断或治疗决策，请以官方工具与临床判断为准。";
        private List<PrepFieldDto> fields = new ArrayList<>();
        private AssessmentSnapshotDto latest;
    }

    @Getter
    @Setter
    public static class PrepFieldDto {
        private String code;
        private String label;
        private boolean ready;
        private String value;
        private String hint;
    }
}
