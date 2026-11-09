package com.healix.core.questionnaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.field.FieldCatalog;
import org.junit.jupiter.api.Test;

class QuestionnaireCatalogTest {

    private final FieldCatalog fields = new FieldCatalog();
    private final QuestionnaireCatalog catalog = new QuestionnaireCatalog(fields);

    @Test
    void loadsWlIntake() {
        QuestionnaireDef q = catalog.latest("WL_INTAKE");
        assertEquals(3, q.version());
        assertNull(q.program());
        assertFalse(catalog.itemsOf(q).isEmpty());
        assertTrue(catalog.itemsOf(q).stream().anyMatch(i -> "REQUIRED".equals(i.level())));
        assertTrue(catalog.module("WL_RISK_SCREEN", 2).isPresent());
        assertTrue(catalog.module("DM_CLINICAL", 2).isPresent());
        assertTrue(catalog.module("HTN_CLINICAL", 2).isPresent());
        assertTrue(q.modules().stream().anyMatch(m -> "GOALS_PREFERENCE".equals(m.code()) && m.version() == 3));
    }

    @Test
    void dropHiddenTrackAnswersOnSubmitShape() {
        QuestionnaireDef q = catalog.latest("WL_INTAKE");
        ObjectNode answers = JsonUtils.emptyObject();
        answers.putArray("goal.careTracks").add("WEIGHT_LOSS");
        answers.put("disease.diabetes.hypoglycemia", "YES");
        answers.put("metric.HEIGHT", "170");
        assertFalse(catalog.fieldVisible(q, "disease.diabetes.hypoglycemia", answers::get));
        assertTrue(catalog.fieldVisible(q, "metric.HEIGHT", answers::get));
        catalog.dropHiddenAnswers(q, answers);
        assertFalse(answers.has("disease.diabetes.hypoglycemia"));
        assertEquals("170", answers.path("metric.HEIGHT").asText());
        assertTrue(answers.has("goal.careTracks"));

        ObjectNode kept = JsonUtils.emptyObject();
        kept.putArray("goal.careTracks").add("DIABETES");
        kept.put("disease.diabetes.hypoglycemia", "YES");
        catalog.dropHiddenAnswers(q, kept);
        assertEquals("YES", kept.path("disease.diabetes.hypoglycemia").asText());
    }

    @Test
    void workScheduleSitsInSleepModule() {
        assertTrue(
                catalog.requireModule("BASIC_PROFILE", 2).items().stream()
                        .noneMatch(i -> "profile.workSchedule".equals(i.field())));
        assertEquals(
                "profile.workSchedule",
                catalog.requireModule("SLEEP_SUBSTANCE", 2).items().get(0).field());
    }

    @Test
    void minorDoesNotSeePregnancyQuestion() {
        ModuleItem repro =
                catalog.requireModule("WOMEN_HEALTH", 2).items().stream()
                        .filter(i -> i.field().equals("basic.women.reproductiveStatus"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(
                repro.showIf()
                        .matches(
                                f -> {
                                    if ("profile.gender".equals(f)) return TextNode.valueOf("FEMALE");
                                    if ("profile.birthday".equals(f)) return TextNode.valueOf("2015-01-01");
                                    return null;
                                }));
        assertTrue(
                repro.showIf()
                        .matches(
                                f -> {
                                    if ("profile.gender".equals(f)) return TextNode.valueOf("FEMALE");
                                    if ("profile.birthday".equals(f)) return TextNode.valueOf("1990-01-01");
                                    return null;
                                }));
    }

    @Test
    void sceneQuestionsStayOnFirstVisitAndExtremesStayWithStaff() {
        var diet = catalog.requireModule("DIET_BEHAVIOR", 2);
        assertTrue(diet.items().stream().anyMatch(i ->
                "basic.diet.dinnerTime".equals(i.field()) && "FIRST_VISIT".equals(i.level())));
        assertTrue(diet.items().stream().anyMatch(i ->
                "basic.diet.staple".equals(i.field()) && "STAFF".equals(i.level())));
        assertTrue(diet.items().stream().anyMatch(i ->
                "basic.diet.cooking".equals(i.field()) && "STAFF".equals(i.level())));
        assertEquals(
                "MULTI",
                fields.require("goal.constraints").type().name());
        assertTrue(PatientCQuickFields.includes("medication.mealRelation"));
        assertTrue(PatientCQuickFields.includes("basic.sleep.refreshed"));
        assertTrue(PatientCQuickFields.includes("basic.schedule.offWork"));
        assertTrue(PatientCQuickFields.includes("basic.schedule.offWorkNote"));
        // C_FAST：代谢 + 红线 + 粗生活方式；不含腰围/血压/晚餐细节
        assertTrue(PatientCFastFields.includes("goal.primaryAppeal"));
        assertTrue(PatientCFastFields.includes("basic.medical.exerciseRestriction"));
        assertTrue(PatientCFastFields.includes("allergy.status"));
        assertTrue(PatientCFastFields.includes("basic.medicationFlags"));
        assertTrue(PatientCFastFields.includes("basic.diet.takeoutFrequency"));
        assertFalse(PatientCFastFields.includes("metric.WAIST"));
        assertFalse(PatientCFastFields.includes("basic.diet.dinnerTime"));
        assertFalse(PatientCFastFields.includes("medication.mealRelation"));
        var sleep = catalog.requireModule("SLEEP_SUBSTANCE", 2);
        assertTrue(sleep.items().stream().anyMatch(i ->
                "basic.schedule.offWork".equals(i.field())
                        && i.showIf() != null
                        && i.showIf().matches(f ->
                                "profile.workSchedule".equals(f) ? TextNode.valueOf("DAY") : null)
                        && !i.showIf().matches(f ->
                                "profile.workSchedule".equals(f) ? TextNode.valueOf("NIGHT") : null)));
        assertTrue(sleep.items().stream().anyMatch(i ->
                "basic.schedule.offWorkNote".equals(i.field())
                        && i.showIf() != null
                        && i.showIf().matches(f ->
                                "profile.workSchedule".equals(f) ? TextNode.valueOf("SHIFT") : null)));
        assertTrue(catalog.requireModule("EXERCISE_BEHAVIOR", 2).items().stream()
                .noneMatch(i -> "basic.schedule.offWork".equals(i.field())));
        assertTrue(
                catalog.requireModule("WL_RISK_SCREEN", 2).items().stream()
                        .noneMatch(i -> "basic.psych.bodySatisfaction".equals(i.field())
                                || "basic.psych.bodyAnxiety".equals(i.field())));
        assertTrue(
                catalog.requireModule("HTN_CLINICAL", 2).items().stream()
                        .filter(i -> "disease.hypertension.highestSystolic".equals(i.field()))
                        .anyMatch(i -> "STAFF".equals(i.level())));
    }

    @Test
    void showIfGender() {
        ModuleItem women =
                catalog.requireModule("WOMEN_HEALTH", 2).items().stream()
                        .filter(i -> i.field().equals("basic.women.reproductiveStatus"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(women.showIf().matches(c -> "profile.gender".equals(c) ? TextNode.valueOf("FEMALE") : null));
        assertFalse(women.showIf().matches(c -> "profile.gender".equals(c) ? TextNode.valueOf("MALE") : null));
    }

    @Test
    void highlightEatingDisorder() {
        ModuleItem item =
                catalog.requireModule("WL_RISK_SCREEN", 2).items().stream()
                        .filter(i -> i.field().equals("basic.psych.eatingDisorderHistory"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(item.highlights().isEmpty());
        assertTrue(item.highlights().stream().anyMatch(h -> h.matches(TextNode.valueOf("YES"))));
        assertTrue(item.highlights().stream().anyMatch(h -> "REFER".equals(h.severity())));
    }
}
