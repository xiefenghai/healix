package com.healix.core.persona.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.adherence.dto.AdherencePatientSummaryDto;
import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.allergy.domain.PeopleAllergy;
import com.healix.core.allergy.mapper.PeopleAllergyMapper;
import com.healix.core.assessment.dto.AssessmentTagView;
import com.healix.core.assessment.support.AssessmentTagAssembler;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.goal.domain.PeopleHealthGoal;
import com.healix.core.goal.mapper.PeopleHealthGoalMapper;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.questionnaire.domain.QuestionnaireResponse;
import com.healix.core.questionnaire.mapper.QuestionnaireResponseMapper;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 规则聚合用户画像六维（不调 LLM）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaDimensionAggregator {

    private final PeopleProfileMapper peopleProfileMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final CareTeamMapper careTeamMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final CarePlanMapper carePlanMapper;
    private final CarePlanVersionMapper carePlanVersionMapper;
    private final PeopleHealthGoalMapper healthGoalMapper;
    private final AssessmentTagAssembler assessmentTagAssembler;
    private final PeopleAllergyMapper allergyMapper;
    private final AdherenceQueryService adherenceQueryService;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final QuestionnaireResponseMapper questionnaireResponseMapper;

    public ObjectNode aggregate(String tenantId, String orgId, String peopleId) {
        ObjectNode root = JsonUtils.emptyObject();
        ObjectNode identity = root.putObject("identity");
        ObjectNode goals = root.putObject("goals");
        ObjectNode risk = root.putObject("risk");
        ObjectNode constraints = root.putObject("constraints");
        ObjectNode behavior = root.putObject("behavior");
        ObjectNode execution = root.putObject("execution");
        ObjectNode focus = root.putObject("focus");

        fillIdentity(identity, tenantId, orgId, peopleId);
        fillGoals(goals, tenantId, peopleId);
        fillRisk(risk, tenantId, orgId, peopleId);
        fillConstraints(constraints, tenantId, peopleId);
        fillBehavior(behavior, tenantId, peopleId);
        fillExecution(execution, tenantId, orgId, peopleId);
        fillFocus(focus, orgId, peopleId, execution);

        root.put("aggregatedAt", LocalDateTime.now().toString());
        String fp = fingerprint(root);
        root.put("sourceFingerprint", fp);
        return root;
    }

    private void fillIdentity(ObjectNode identity, String tenantId, String orgId, String peopleId) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile != null) {
            if (StringUtils.hasText(profile.getGender())) {
                identity.put("sex", profile.getGender());
            }
            if (profile.getBirthday() != null) {
                identity.put("age", Period.between(profile.getBirthday(), LocalDate.now()).getYears());
            }
        }

        PatientCareAssignment assignment = careAssignmentMapper.find(tenantId, peopleId);
        if (assignment != null && StringUtils.hasText(assignment.getPrimaryCareManagerStaffId())) {
            StaffProfile staff = staffProfileMapper.findById(assignment.getPrimaryCareManagerStaffId());
            if (staff != null && StringUtils.hasText(staff.getDisplayName())) {
                identity.put("primaryCareManagerName", staff.getDisplayName());
            }
        }

        CareTeamMember member = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        if (member != null && StringUtils.hasText(member.getTeamId())) {
            CareTeam team = careTeamMapper.findById(member.getTeamId());
            if (team != null && StringUtils.hasText(team.getName())) {
                identity.put("careTeamName", team.getName());
            }
        }

        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (membership != null && membership.getJoinedAt() != null) {
            long days = ChronoUnit.DAYS.between(membership.getJoinedAt().toLocalDate(), LocalDate.now());
            identity.put("enrolledDays", Math.max(0, days));
        }

        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan != null && "ACTIVE".equals(plan.getStatus())) {
            if (StringUtils.hasText(plan.getDiseaseTagsJson())) {
                try {
                    JsonNode tags = JsonUtils.readTree(plan.getDiseaseTagsJson());
                    if (tags.isArray()) {
                        identity.set("diseaseTags", tags);
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
            if (StringUtils.hasText(plan.getCurrentVersionId())) {
                CarePlanVersion ver = carePlanVersionMapper.findById(plan.getCurrentVersionId());
                if (ver != null && StringUtils.hasText(ver.getContextSnapshotJson())) {
                    try {
                        JsonNode snap = JsonUtils.readTree(ver.getContextSnapshotJson());
                        String key = snap.path("templateKey").asText(null);
                        if (StringUtils.hasText(key)) {
                            identity.put("activePlanTemplateKey", key);
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    private void fillGoals(ObjectNode goals, String tenantId, String peopleId) {
        PeopleHealthGoal goal = healthGoalMapper.findLatestByPeople(tenantId, peopleId);
        if (goal != null) {
            if (StringUtils.hasText(goal.getAppealText())) {
                goals.put("summary", goal.getAppealText());
            } else if (StringUtils.hasText(goal.getPrimaryAppeal())) {
                goals.put("summary", goal.getPrimaryAppeal());
            }
            if (StringUtils.hasText(goal.getGoalMode())) {
                goals.put("mode", goal.getGoalMode());
            }
            if (StringUtils.hasText(goal.getGoalsJson())) {
                try {
                    JsonNode g = JsonUtils.readTree(goal.getGoalsJson());
                    JsonNode tracks = g.path("careTracks");
                    if (tracks.isArray() && !tracks.isEmpty()) {
                        goals.set("careTracks", tracks);
                    }
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }

        // 首诊标记
        try {
            List<QuestionnaireResponse> rows = questionnaireResponseMapper.listByPeople(tenantId, peopleId);
            for (QuestionnaireResponse r : rows) {
                if (!"SUBMITTED".equals(r.getStatus())) {
                    continue;
                }
                if (!"INTAKE".equals(r.getPurpose()) && !"SUPPLEMENT".equals(r.getPurpose())) {
                    continue;
                }
                goals.put("fromIntake", true);
                break;
            }
        } catch (Exception e) {
            log.debug("[Persona] load intake for goals failed peopleId={}", peopleId, e);
        }
    }

    private void fillRisk(ObjectNode risk, String tenantId, String orgId, String peopleId) {
        ArrayNode tags = risk.putArray("tags");
        List<AssessmentTagView> list = assessmentTagAssembler.assemble(tenantId, orgId, peopleId);
        for (AssessmentTagView t : list) {
            ObjectNode n = tags.addObject();
            n.put("diseaseCode", t.getDiseaseCode());
            n.put("category", t.getCategory());
            n.put("level", t.getLevel());
            n.put("levelLabel", t.getText() != null ? t.getText() : t.getLevel());
            if (StringUtils.hasText(t.getTier())) {
                n.put("tier", t.getTier());
            }
        }
    }

    private void fillConstraints(ObjectNode constraints, String tenantId, String peopleId) {
        ArrayNode allergens = constraints.putArray("allergens");
        ArrayNode notes = constraints.putArray("notes");
        ArrayNode highlightCodes = constraints.putArray("highlightCodes");
        try {
            List<PeopleAllergy> rows = allergyMapper.listByPeople(tenantId, peopleId);
            for (PeopleAllergy a : rows) {
                if (a == null || !StringUtils.hasText(a.getAllergenName())) {
                    continue;
                }
                if ("RESOLVED".equalsIgnoreCase(a.getClinicalStatus())
                        || "REFUTED".equalsIgnoreCase(a.getVerification())) {
                    continue;
                }
                allergens.add(a.getAllergenName().trim());
            }
        } catch (Exception e) {
            log.debug("[Persona] load allergy failed peopleId={}", peopleId, e);
        }

        try {
            QuestionnaireResponse intake = latestSubmittedIntake(tenantId, peopleId);
            if (intake == null || !StringUtils.hasText(intake.getHighlightsJson())) {
                return;
            }
            JsonNode hs = JsonUtils.readTree(intake.getHighlightsJson());
            if (hs == null || !hs.isArray()) {
                return;
            }
            for (JsonNode h : hs) {
                appendConstraintHighlight(highlightCodes, notes, h);
            }
        } catch (Exception e) {
            log.debug("[Persona] load highlights failed peopleId={}", peopleId, e);
        }
    }

    /**
     * 备注只用方案影响（planEffect）；禁止回退到题干 label。
     * 高亮码用 severity（CAUTION/REFER/BLOCK）。
     */
    static void appendConstraintHighlight(ArrayNode highlightCodes, ArrayNode notes, JsonNode h) {
        if (h == null || h.isNull()) {
            return;
        }
        String severity = textOrNull(h, "severity");
        if (StringUtils.hasText(severity)) {
            highlightCodes.add(severity.trim().toUpperCase());
        }
        String note = textOrNull(h, "planEffect");
        if (!StringUtils.hasText(note)) {
            note = textOrNull(h, "note");
        }
        // 明确不使用 label（题干）
        if (StringUtils.hasText(note)) {
            notes.add(note.trim());
        }
    }

    private void fillBehavior(ObjectNode behavior, String tenantId, String peopleId) {
        ArrayNode diet = behavior.putArray("diet");
        ArrayNode exercise = behavior.putArray("exercise");
        ArrayNode psych = behavior.putArray("psych");
        try {
            QuestionnaireResponse intake = latestSubmittedIntake(tenantId, peopleId);
            if (intake == null || !StringUtils.hasText(intake.getAnswersJson())) {
                return;
            }
            JsonNode answers = JsonUtils.readTree(intake.getAnswersJson());
            if (answers == null || !answers.isObject()) {
                return;
            }
            appendBehaviorTags(diet, exercise, psych, answers);
        } catch (Exception e) {
            log.debug("[Persona] load behavior from intake failed peopleId={}", peopleId, e);
        }
    }

    /** 从首诊答案提炼开方抓手标签（饮食 / 运动 / 心理）。 */
    static void appendBehaviorTags(
            ArrayNode diet, ArrayNode exercise, ArrayNode psych, JsonNode answers) {
        String takeout = textAns(answers, "basic.diet.takeoutFrequency");
        if ("WEEKLY_4_7".equals(takeout) || "DAILY_MULTI".equals(takeout)) {
            diet.add("外卖高频");
        } else if ("WEEKLY_1_3".equals(takeout)) {
            diet.add("偶尔外卖");
        }
        String cook = textAns(answers, "basic.diet.cook");
        if ("FAMILY".equals(cook)) {
            diet.add("家人做饭");
        } else if ("EAT_OUT".equals(cook)) {
            diet.add("外卖/外食为主");
        } else if ("SELF".equals(cook)) {
            diet.add("自己做饭");
        }
        if (multiHas(answers, "basic.diet.taste", "SALTY")) {
            diet.add("口味偏咸");
        }
        if (multiHas(answers, "basic.diet.taste", "OILY")) {
            diet.add("偏油");
        }
        if (multiHas(answers, "basic.diet.taste", "SWEET")) {
            diet.add("偏甜");
        }
        String snack = textAns(answers, "basic.diet.snackNight");
        if ("MOST_DAYS".equals(snack)) {
            diet.add("常吃夜宵");
        }
        String sugar = textAns(answers, "basic.diet.sugaryDrinks");
        if ("DAILY_1".equals(sugar) || "DAILY_2PLUS".equals(sugar)) {
            diet.add("含糖饮料多");
        }

        String freq = textAns(answers, "basic.exercise.frequency");
        if ("NONE".equals(freq) || "RARELY".equals(freq)) {
            exercise.add("几乎不运动");
        } else if (StringUtils.hasText(freq) && !"NONE".equals(freq)) {
            exercise.add("有运动习惯");
        }
        if (multiHas(answers, "basic.exercise.types", "WALK")) {
            exercise.add("偏好快走");
        }
        if (multiHas(answers, "basic.exercise.types", "STRENGTH")) {
            exercise.add("有力量训练");
        }
        JsonNode sedentary = answers.get("basic.exercise.sedentaryHours");
        if (sedentary != null && sedentary.isNumber() && sedentary.asDouble() >= 8) {
            exercise.add("久坐（≥8h）");
        }
        String strength = textAns(answers, "basic.exercise.strengthExperience");
        if ("NONE".equals(strength)) {
            exercise.add("力量零基础");
        }

        String stressEat = textAns(answers, "basic.psych.stressEating");
        // catalog: NO / EAT_MORE / EAT_LESS
        if ("EAT_MORE".equals(stressEat)) {
            psych.add("压力性进食");
        }
        String emoEat = textAns(answers, "basic.psych.emotionalEating");
        // catalog: NEVER / SOMETIMES / OFTEN
        if ("OFTEN".equals(emoEat) || "SOMETIMES".equals(emoEat)) {
            psych.add("情绪性进食");
        }
        String pace = textAns(answers, "basic.weightHistory.expectedPace");
        if ("ASAP".equals(pace)) {
            psych.add("求速减重");
        } else if ("STEADY".equals(pace)) {
            psych.add("接受稳健节奏");
        }
        String readiness = textAns(answers, "basic.psych.readiness");
        if ("HIGH".equals(readiness) || "READY".equals(readiness)) {
            psych.add("改变意愿高");
        }
        String ed = textAns(answers, "basic.psych.eatingDisorderHistory");
        if ("YES".equals(ed) || "UNSURE".equals(ed)) {
            psych.add("进食障碍风险需关注");
        }
    }

    private QuestionnaireResponse latestSubmittedIntake(String tenantId, String peopleId) {
        List<QuestionnaireResponse> rows = questionnaireResponseMapper.listByPeople(tenantId, peopleId);
        for (QuestionnaireResponse r : rows) {
            if (!"SUBMITTED".equals(r.getStatus())) {
                continue;
            }
            if (!"INTAKE".equals(r.getPurpose()) && !"SUPPLEMENT".equals(r.getPurpose())) {
                continue;
            }
            return r;
        }
        return null;
    }

    private void fillExecution(ObjectNode execution, String tenantId, String orgId, String peopleId) {
        // 激励火苗尚未接入画像；不写 flameStatus，前端隐藏该行
        execution.putNull("medMissStreak");
        try {
            AdherencePatientSummaryDto summary =
                    adherenceQueryService.patientSummary(tenantId, orgId, peopleId, LocalDate.now());
            boolean hasPlan = summary.isHasActivePlan();
            execution.put("hasActivePlan", hasPlan);
            if (summary.getRate() != null) {
                execution.put("planCompletion7d", summary.getRate());
            } else {
                execution.putNull("planCompletion7d");
            }
            execution.put("planMissStreak", summary.getStreakDays());
            if (summary.getMedPercent() != null) {
                // 用当日用药完成率近似；7 日用药率后续可对齐看板
                execution.put("medCompletion7d", summary.getMedPercent() / 100.0d);
            } else {
                execution.putNull("medCompletion7d");
            }
        } catch (Exception e) {
            log.debug("[Persona] adherence failed peopleId={}", peopleId, e);
            execution.put("hasActivePlan", false);
            execution.putNull("planCompletion7d");
            execution.put("planMissStreak", 0);
            execution.putNull("medCompletion7d");
        }
    }

    private void fillFocus(ObjectNode focus, String orgId, String peopleId, ObjectNode execution) {
        ArrayNode openTasks = focus.putArray("openTasks");
        try {
            List<WorkspaceTask> tasks = workspaceTaskMapper.listOpenByPeople(orgId, peopleId);
            int n = 0;
            for (WorkspaceTask t : tasks) {
                if (n >= 5) {
                    break;
                }
                ObjectNode item = openTasks.addObject();
                item.put("type", t.getTaskType());
                String title = StringUtils.hasText(t.getTitle())
                        ? t.getTitle()
                        : (StringUtils.hasText(t.getSummary()) ? t.getSummary() : t.getTaskType());
                item.put("title", title);
                n++;
            }
        } catch (Exception e) {
            log.debug("[Persona] open tasks failed peopleId={}", peopleId, e);
        }

        String next = ruleNextStep(openTasks, execution);
        if (StringUtils.hasText(next)) {
            focus.put("nextStep", next);
        } else {
            focus.putNull("nextStep");
        }
    }

    private static String ruleNextStep(ArrayNode openTasks, ObjectNode execution) {
        if (openTasks != null && !openTasks.isEmpty()) {
            String title = openTasks.get(0).path("title").asText(null);
            if (StringUtils.hasText(title)) {
                return "优先处理：" + title;
            }
        }
        int streak = execution.path("planMissStreak").asInt(0);
        if (streak >= 3) {
            return "先电话确认连续 " + streak + " 日方案未完成原因";
        }
        JsonNode rate = execution.get("planCompletion7d");
        if (rate != null && !rate.isNull() && rate.asDouble() < 0.6d) {
            return "近 7 日方案完成率偏低，核对打卡障碍后再决定是否调方案";
        }
        return null;
    }

    private static String fingerprint(ObjectNode dimensions) {
        try {
            // 去掉易变时间戳再哈希
            ObjectNode copy = dimensions.deepCopy();
            copy.remove("aggregatedAt");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(JsonUtils.toJson(copy).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return "sha256:na";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return StringUtils.hasText(s) ? s : null;
    }

    private static String textAns(JsonNode answers, String field) {
        if (answers == null) {
            return null;
        }
        JsonNode v = answers.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual() || v.isNumber() || v.isBoolean()) {
            String s = v.asText(null);
            return StringUtils.hasText(s) ? s.trim() : null;
        }
        return null;
    }

    private static boolean multiHas(JsonNode answers, String field, String code) {
        if (answers == null || !StringUtils.hasText(code)) {
            return false;
        }
        JsonNode v = answers.get(field);
        if (v == null || v.isNull()) {
            return false;
        }
        if (v.isArray()) {
            for (JsonNode n : v) {
                if (code.equalsIgnoreCase(n.asText(""))) {
                    return true;
                }
            }
            return false;
        }
        return code.equalsIgnoreCase(v.asText(""));
    }
}
