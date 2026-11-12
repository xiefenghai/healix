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
}
