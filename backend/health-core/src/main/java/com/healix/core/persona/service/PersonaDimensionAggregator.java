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
        ObjectNode execution = root.putObject("execution");
        ObjectNode focus = root.putObject("focus");

        fillIdentity(identity, tenantId, orgId, peopleId);
        fillGoals(goals, tenantId, peopleId);
        fillRisk(risk, tenantId, orgId, peopleId);
        fillConstraints(constraints, tenantId, peopleId);
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
            List<QuestionnaireResponse> rows = questionnaireResponseMapper.listByPeople(tenantId, peopleId);
            for (QuestionnaireResponse r : rows) {
                if (!"SUBMITTED".equals(r.getStatus())) {
                    continue;
                }
                if (!"INTAKE".equals(r.getPurpose()) && !"SUPPLEMENT".equals(r.getPurpose())) {
                    continue;
                }
                if (!StringUtils.hasText(r.getHighlightsJson())) {
                    break;
                }
                JsonNode hs = JsonUtils.readTree(r.getHighlightsJson());
                if (hs != null && hs.isArray()) {
                    for (JsonNode h : hs) {
                        String code = h.path("code").asText(null);
                        if (!StringUtils.hasText(code)) {
                            code = h.path("type").asText(null);
                        }
                        if (StringUtils.hasText(code)) {
                            highlightCodes.add(code);
                        }
                        String note = h.path("note").asText(null);
                        if (!StringUtils.hasText(note)) {
                            note = h.path("label").asText(null);
                        }
                        if (StringUtils.hasText(note)) {
                            notes.add(note);
                        }
                    }
                }
                break;
            }
        } catch (Exception e) {
            log.debug("[Persona] load highlights failed peopleId={}", peopleId, e);
        }
    }

    private void fillExecution(ObjectNode execution, String tenantId, String orgId, String peopleId) {
        execution.putNull("flameStatus");
        execution.putNull("medMissStreak");
        try {
            AdherencePatientSummaryDto summary =
                    adherenceQueryService.patientSummary(tenantId, orgId, peopleId, LocalDate.now());
            if (summary.getRate() != null) {
                execution.put("planCompletion7d", summary.getRate());
            } else {
                execution.putNull("planCompletion7d");
            }
            execution.put("planMissStreak", summary.getStreakDays());
            if (summary.getMedPercent() != null) {
                // P1：用当日用药完成率近似；7 日用药率后续可对齐看板
                execution.put("medCompletion7d", summary.getMedPercent() / 100.0d);
            } else {
                execution.putNull("medCompletion7d");
            }
        } catch (Exception e) {
            log.debug("[Persona] adherence failed peopleId={}", peopleId, e);
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
}
