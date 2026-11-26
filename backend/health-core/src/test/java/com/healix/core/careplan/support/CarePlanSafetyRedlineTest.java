package com.healix.core.careplan.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.dto.SafetyFlagDto;
import com.healix.core.field.FieldCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarePlanSafetyRedlineTest {

    private final CarePlanSafetyService safety = new CarePlanSafetyService(new FieldCatalog());

    @Test
    void blocksRunningWhenJointIssuesPresent() {
        JsonNode exercise = JsonUtils.readTree(
                """
                {
                  "goal": "每周跑步3次",
                  "contraindications": [],
                  "weeklyPlan": [
                    {"day":"MON","items":[{"type":"RUN","durationMin":30,"intensity":"VIGOROUS","note":"跑步"}]}
                  ]
                }
                """);
        JsonNode diet = JsonUtils.readTree("{\"principles\":[\"均衡\"],\"recommended\":[],\"sampleDay\":{}}");
        JsonNode execution = JsonUtils.readTree(
                "{\"horizonDays\":14,\"tasks\":[{\"code\":\"DIET_LOG\",\"title\":\"记录三餐\",\"category\":\"DIET\",\"frequency\":\"QD\",\"enabled\":true}]}");
        JsonNode snap = JsonUtils.readTree(
                """
                {
                  "safetyRedlines": [
                    {"code":"JOINT_ISSUES","priority":"HIGHEST","mustApplyExercise":"低冲击"}
                  ],
                  "basicArchive": {"medical": {"jointIssues": ["KNEE"]}}
                }
                """);

        List<SafetyFlagDto> flags = safety.evaluate(
                exercise, diet, execution, false, false, false, List.of(), null, snap, false);

        assertThat(flags.stream().map(SafetyFlagDto::code))
                .contains(CarePlanSafetyService.ERR_SAFETY_REDLINE);
        assertThat(flags.stream()
                        .filter(f -> CarePlanSafetyService.ERR_SAFETY_REDLINE.equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .message())
                .contains("跑步");
    }

    @Test
    void allowsLightWalkingEvenIfContraindicationsMentionRunning() {
        // 禁忌写「禁止跑步/跳绳」不得误杀；周计划为 LIGHT 步行应放行
        JsonNode exercise = JsonUtils.readTree(
                """
                {
                  "goal": "术后低冲击恢复：以慢走为主",
                  "contraindications": ["禁止跑步、跳绳、负重与剧烈有氧"],
                  "precautions": ["若出现疼痛立即停止，勿跑步"],
                  "weeklyPlan": [
                    {"day":"MON","items":[{"type":"WALKING","durationMin":20,"intensity":"LIGHT","note":"平路慢走，禁止跑步跳绳"}]}
                  ]
                }
                """);
        JsonNode diet = JsonUtils.readTree("{\"principles\":[\"均衡\"],\"recommended\":[],\"sampleDay\":{}}");
        JsonNode execution = JsonUtils.readTree(
                "{\"horizonDays\":14,\"tasks\":[{\"code\":\"DIET_LOG\",\"title\":\"记录三餐\",\"category\":\"DIET\",\"frequency\":\"QD\",\"enabled\":true}]}");
        JsonNode snap = JsonUtils.readTree(
                """
                {
                  "safetyRedlines": [
                    {"code":"JOINT_ISSUES"},
                    {"code":"EXERCISE_RESTRICTION"}
                  ],
                  "basicArchive": {"medical": {"jointIssues": ["KNEE"], "exerciseRestriction": ["DOCTOR_LIMIT"]}}
                }
                """);

        List<SafetyFlagDto> flags = safety.evaluate(
                exercise, diet, execution, false, false, false, List.of(), null, snap, false);

        assertThat(flags.stream().map(SafetyFlagDto::code))
                .doesNotContain(CarePlanSafetyService.ERR_SAFETY_REDLINE);
    }

    @Test
    void blocksDailyWeighOnEatingDisorder() {
        JsonNode exercise = JsonUtils.readTree(
                """
                {
                  "goal": "餐后步行",
                  "contraindications": ["进食障碍风险"],
                  "weeklyPlan": [
                    {"day":"MON","items":[{"type":"WALKING","durationMin":20,"intensity":"LIGHT"}]}
                  ]
                }
                """);
        JsonNode diet = JsonUtils.readTree("{\"principles\":[\"均衡\"],\"recommended\":[],\"sampleDay\":{}}");
        JsonNode execution = JsonUtils.readTree(
                """
                {
                  "horizonDays":14,
                  "tasks":[
                    {"code":"WEIGHT_LOG","title":"每日称体重","category":"OTHER","frequency":"QD","relatedRef":"WEIGHT","enabled":true},
                    {"code":"DIET_LOG","title":"记录三餐","category":"DIET","frequency":"QD","enabled":true}
                  ]
                }
                """);
        JsonNode snap = JsonUtils.readTree(
                """
                {
                  "safetyRedlines": [{"code":"EATING_DISORDER"}],
                  "basicArchive": {"psych": {"eatingDisorderHistory": "YES"}}
                }
                """);

        List<SafetyFlagDto> flags = safety.evaluate(
                exercise, diet, execution, false, false, false, List.of(), null, snap, false);

        assertThat(flags.stream().map(SafetyFlagDto::code))
                .contains(CarePlanSafetyService.ERR_SAFETY_REDLINE);
        assertThat(flags.stream()
                        .filter(f -> CarePlanSafetyService.ERR_SAFETY_REDLINE.equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .message())
                .contains("每日称重");
    }

    @Test
    void allowsNegatedExtremeDietPhrasesOnEatingDisorder() {
        // 系统模板/红线合规话术含「避免极端节食」「不做极端节食话术」不得误杀
        JsonNode exercise = JsonUtils.readTree(
                """
                {
                  "goal": "餐后步行",
                  "contraindications": ["进食障碍风险：避免以体重为唯一目标的训练"],
                  "weeklyPlan": [
                    {"day":"MON","items":[{"type":"WALKING","durationMin":20,"intensity":"LIGHT"}]}
                  ]
                }
                """);
        JsonNode diet = JsonUtils.readTree(
                """
                {
                  "calorieHint": "每日约 1500 千卡，以规律进食为主；避免极端节食",
                  "principles": [
                    "高优先级安全红线：不设激进热量缺口、不做极端节食话术，重点放在规律三餐"
                  ],
                  "recommended": [],
                  "sampleDay": {}
                }
                """);
        JsonNode execution = JsonUtils.readTree(
                """
                {
                  "horizonDays":14,
                  "tasks":[
                    {"code":"WEIGHT_LOG","title":"每周固定一天晨起空腹称体重","category":"OTHER","frequency":"QW","relatedRef":"WEIGHT","enabled":true}
                  ]
                }
                """);
        JsonNode snap = JsonUtils.readTree(
                """
                {
                  "safetyRedlines": [{"code":"EATING_DISORDER"}],
                  "basicArchive": {"psych": {"eatingDisorderHistory": "YES"}}
                }
                """);

        List<SafetyFlagDto> flags = safety.evaluate(
                exercise, diet, execution, false, false, false, List.of(), null, snap, false);

        assertThat(flags.stream().map(SafetyFlagDto::code))
                .doesNotContain(CarePlanSafetyService.ERR_SAFETY_REDLINE);
    }

    @Test
    void blocksPositiveExtremeDietPhraseOnEatingDisorder() {
        JsonNode exercise = JsonUtils.readTree(
                """
                {
                  "goal": "餐后步行",
                  "weeklyPlan": [
                    {"day":"MON","items":[{"type":"WALKING","durationMin":20,"intensity":"LIGHT"}]}
                  ]
                }
                """);
        JsonNode diet = JsonUtils.readTree(
                """
                {
                  "calorieHint": "本周目标：极端节食冲刺，快速见效",
                  "principles": ["少吃多动"],
                  "recommended": [],
                  "sampleDay": {}
                }
                """);
        JsonNode execution = JsonUtils.readTree(
                "{\"horizonDays\":14,\"tasks\":[{\"code\":\"DIET_LOG\",\"title\":\"记录三餐\",\"category\":\"DIET\",\"frequency\":\"QD\",\"enabled\":true}]}");
        JsonNode snap = JsonUtils.readTree(
                """
                {
                  "safetyRedlines": [{"code":"EATING_DISORDER"}],
                  "basicArchive": {"psych": {"eatingDisorderHistory": "YES"}}
                }
                """);

        List<SafetyFlagDto> flags = safety.evaluate(
                exercise, diet, execution, false, false, false, List.of(), null, snap, false);

        assertThat(flags.stream().map(SafetyFlagDto::code))
                .contains(CarePlanSafetyService.ERR_SAFETY_REDLINE);
        assertThat(flags.stream()
                        .filter(f -> CarePlanSafetyService.ERR_SAFETY_REDLINE.equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .message())
                .contains("极端节食");
    }
}
