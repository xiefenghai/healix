package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.diet.enums.ActivityLevelEnum;
import com.healix.core.diet.enums.DietGoalTypeEnum;
import com.healix.core.diet.support.EnergyTargetCalculator;
import org.junit.jupiter.api.Test;

class CarePlanEnergyTargetServiceTest {

    @Test
    void loseHalfKgPerWeekSubtractsAbout500KcalFromTdee() {
        var maintain = EnergyTargetCalculator.calculate(new EnergyTargetCalculator.Input(
                "MALE",
                40,
                175,
                86,
                ActivityLevelEnum.MODERATE,
                DietGoalTypeEnum.MAINTAIN,
                0,
                CarePlanTemplateKeyEnum.GENERAL));
        var lose = EnergyTargetCalculator.calculate(new EnergyTargetCalculator.Input(
                "MALE",
                40,
                175,
                86,
                ActivityLevelEnum.MODERATE,
                DietGoalTypeEnum.LOSE,
                0.5,
                CarePlanTemplateKeyEnum.GENERAL));
        int deficit = maintain.energyKcal() - lose.energyKcal();
        assertTrue(deficit >= 500 && deficit <= 600, "deficit=" + deficit);
        assertTrue(lose.energyKcal() < maintain.energyKcal());
    }

    @Test
    void alignSummaryKcalReplacesMismatchedNumber() {
        ObjectNode snap = JsonUtils.emptyObject();
        snap.put("summary", "阶段目标：12 周减重约 6kg；总热量约 2120 千卡，配合规律运动。");
        ObjectNode target = JsonUtils.emptyObject();
        target.put("energyKcal", 1680);
        CarePlanEnergyTargetService.alignSummaryKcal(snap, target);
        assertEquals("阶段目标：12 周减重约 6kg；总热量约 1680 千卡，配合规律运动。", snap.path("summary").asText());
    }

    @Test
    void alignSummaryKcalKeepsMatchingNumber() {
        ObjectNode snap = JsonUtils.emptyObject();
        snap.put("summary", "每日总热量约 1680 千卡。");
        ObjectNode target = JsonUtils.emptyObject();
        target.put("energyKcal", 1680);
        CarePlanEnergyTargetService.alignSummaryKcal(snap, target);
        assertEquals("每日总热量约 1680 千卡。", snap.path("summary").asText());
    }

    @Test
    void looksLikeLoseCopyDetectsSteadyDropWithout减重Keyword() {
        String summary =
                "12周内体重从约86kg稳步下降约6kg（每周约0.5kg），家庭血压逐步靠近130/80mmHg";
        assertTrue(CarePlanEnergyTargetService.looksLikeLoseCopy(summary));
    }

    @Test
    void sanitizeLoseNarrativeSoftensDropCopy() {
        ObjectNode snap = JsonUtils.emptyObject();
        snap.put(
                "summary",
                "12周内体重从约86kg稳步下降约6kg（每周约0.5kg），饮食上限盐少油。");
        CarePlanEnergyTargetService.sanitizeLoseNarrative(snap);
        String next = snap.path("summary").asText();
        assertTrue(next.contains("体重保持相对平稳"));
        assertTrue(next.contains("体重波动可控"));
    }
}
