package com.healix.core.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ComplianceTextTest {

    @Test
    void rewritesAffirmativeWording() {
        assertEquals("按时服药", ComplianceText.filter("按医嘱服药"));
        assertEquals("按时监测血糖", ComplianceText.filter("按医嘱监测血糖"));
        assertEquals("请按医生建议调整", ComplianceText.filter("请遵医嘱调整"));
        assertEquals("本周运动计划：快走 30 分钟", ComplianceText.filter("本周运动处方：快走 30 分钟"));
        assertEquals("已患糖尿病 3 年", ComplianceText.filter("确诊糖尿病 3 年"));
        assertEquals("已患高血压", ComplianceText.filter("已确诊高血压"));
        assertEquals("调整管理方案", ComplianceText.filter("调整治疗方案"));
    }

    @Test
    void keepsNegatedDisclaimerClause() {
        String s = "以上内容仅供参考，不构成医学诊断或处方。请按医嘱服药";
        assertEquals("以上内容仅供参考，不构成医学诊断或处方。请按时服药", ComplianceText.filter(s));
        assertEquals("非诊断结论", ComplianceText.filter("非诊断结论"));
    }

    @Test
    void passesThroughCleanText() {
        assertNull(ComplianceText.filter(null));
        assertEquals("", ComplianceText.filter(""));
        assertEquals("今天血压 128/82，控制不错。", ComplianceText.filter("今天血压 128/82，控制不错。"));
        assertEquals("线下就诊时带上记录", ComplianceText.filter("线下就诊时带上记录"));
    }
}
