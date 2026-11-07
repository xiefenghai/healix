package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class CarePlanRationaleSupportTest {

    @Test
    void scrubText_stripsGuidelineCodesAndKeepsMeaning() {
        String raw =
                "按WL.ENERGY_DEFICIT与WL.RATE控制每日约1600千卡、每周减重约0.5kg；按DM.DIET.CARB控精制糖与精白米面、主食粗细搭配，"
                        + "T1糖尿病故示例餐不用粥类；按HTN.DIET.SALT与GEN.DIET.SALT限盐<6g；按GEN.DIET.OIL_SUGAR、GEN.DIET.VEG_FRUIT落实油糖与蔬果。"
                        + "鸡蛋为中度过敏原，全部示例餐与推荐食物避开并给平替。降压药需与第一口饭同服，写入notes。";
        var sr = CarePlanRationaleSupport.scrubText(raw);
        assertFalse(sr.text().contains("WL.ENERGY_DEFICIT"));
        assertFalse(sr.text().contains("DM.DIET.CARB"));
        assertFalse(sr.text().contains("HTN.DIET.SALT"));
        assertTrue(sr.text().contains("每日约1600千卡"));
        assertTrue(sr.text().contains("每周减重约0.5kg"));
        assertTrue(sr.text().contains("限盐"));
        assertTrue(sr.text().contains("鸡蛋"));
        assertTrue(sr.codes().contains("WL.ENERGY_DEFICIT"));
        assertTrue(sr.codes().contains("DM.DIET.CARB"));
        assertTrue(sr.codes().contains("GEN.DIET.VEG_FRUIT"));
    }

    @Test
    void scrubGuidelineCodesInPlan_mergesCodesIntoRefs() {
        ObjectNode diet = JsonUtils.emptyObject();
        diet.put(
                "rationale",
                "按HTN.DIET.SALT与GEN.DIET.SALT限盐<6g；鸡蛋过敏避开。");
        diet.putArray("refs").add("HTN.DIET.SALT");
        ObjectNode snap = JsonUtils.emptyObject();
        ObjectNode rationale = snap.putObject("rationale");
        rationale.put("overview", "综合管理，依据 WL.RATE 与 DM.DIET.CARB。");

        CarePlanRationaleSupport.scrubGuidelineCodesInPlan(null, diet, null, snap);

        assertFalse(diet.path("rationale").asText().contains("HTN.DIET.SALT"));
        assertTrue(diet.path("rationale").asText().contains("限盐"));
        assertEquals(2, diet.path("refs").size());
        assertTrue(diet.path("refs").toString().contains("GEN.DIET.SALT"));
        assertFalse(snap.path("rationale").path("overview").asText().contains("WL.RATE"));
    }
}
