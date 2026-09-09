package com.healix.core.notify.service;

import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.adherence.service.AdherenceQueryService.DailyHealthTodoCandidate;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.dto.NotifyPublishResult;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 刀2：日批扫描方案/用药未完成，发 {@link NotifyEventType#DAILY_HEALTH_TODO} digest。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyReminderScanService {

    private static final int BATCH_SIZE = 200;

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final AccountPatientMapper accountPatientMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final AdherenceQueryService adherenceQueryService;
    private final NotifyFacade notifyFacade;

    public record ScanCounts(int orgs, int candidates, int created, int upserted, int skippedNoRecipient) {}

    public ScanCounts scanAll(LocalDate day) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        int orgs = 0;
        int candidates = 0;
        int created = 0;
        int upserted = 0;
        int skippedNoRecipient = 0;
        Set<String> publishedPeople = new LinkedHashSet<>();
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                orgs++;
                ScanCounts c = scanOrg(tenant.getId(), org.getId(), scanDay, publishedPeople);
                candidates += c.candidates();
                created += c.created();
                upserted += c.upserted();
                skippedNoRecipient += c.skippedNoRecipient();
            }
        }
        return new ScanCounts(orgs, candidates, created, upserted, skippedNoRecipient);
    }

    public ScanCounts scanOrg(
            String tenantId, String orgId, LocalDate day, Set<String> alreadyPublishedPeople) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        List<String> allPeopleIds = new ArrayList<>();
        for (PatientOrgMembership m : membershipMapper.listActiveByOrg(orgId)) {
            if (!MembershipStatusEnum.ACTIVE.matches(m.getStatus())) {
                continue;
            }
            if (!StringUtils.hasText(m.getPeopleId())) {
                continue;
            }
            if (alreadyPublishedPeople != null && alreadyPublishedPeople.contains(m.getPeopleId())) {
                continue;
            }
            allPeopleIds.add(m.getPeopleId());
        }
        if (allPeopleIds.isEmpty()) {
            return new ScanCounts(1, 0, 0, 0, 0);
        }

        int candidates = 0;
        int created = 0;
        int upserted = 0;
        int skippedNoRecipient = 0;

        for (int i = 0; i < allPeopleIds.size(); i += BATCH_SIZE) {
            List<String> batch = allPeopleIds.subList(i, Math.min(i + BATCH_SIZE, allPeopleIds.size()));
            List<String> linked = accountPatientMapper.listLinkedPeopleIds(batch);
            if (linked.isEmpty()) {
                continue;
            }
            Map<String, String> names = loadDisplayNames(linked);
            List<DailyHealthTodoCandidate> todos =
                    adherenceQueryService.listDailyHealthTodos(tenantId, linked, names, scanDay);
            for (DailyHealthTodoCandidate todo : todos) {
                candidates++;
                NotifyPublishResult result = publishDigest(tenantId, orgId, scanDay, todo);
                if (result.getMessageIds().isEmpty()) {
                    skippedNoRecipient++;
                    continue;
                }
                int existing = result.getSkippedExistingIds().size();
                upserted += existing;
                created += Math.max(0, result.getMessageIds().size() - existing);
                if (alreadyPublishedPeople != null) {
                    alreadyPublishedPeople.add(todo.peopleId());
                }
            }
        }
        return new ScanCounts(1, candidates, created, upserted, skippedNoRecipient);
    }

    private NotifyPublishResult publishDigest(
            String tenantId, String orgId, LocalDate day, DailyHealthTodoCandidate todo) {
        String name = StringUtils.hasText(todo.displayName()) ? todo.displayName() : "家人";
        String body = buildBody(name, todo);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("day", day.toString());
        payload.put("peopleId", todo.peopleId());
        payload.put("planDue", todo.planDue());
        payload.put("planIncomplete", todo.planIncomplete());
        payload.put("planIncompleteFlag", todo.planIncompleteFlag());
        payload.put("medActive", todo.medActive());
        payload.put("medTaken", todo.medTaken());
        payload.put("medDueDoses", todo.medDueDoses());
        payload.put("medTakenDoses", todo.medTakenDoses());
        payload.put("medIncompleteFlag", todo.medIncompleteFlag());
        try {
            return notifyFacade.publish(NotifyPublishCommand.builder()
                    .tenantId(tenantId)
                    .eventType(NotifyEventType.DAILY_HEALTH_TODO)
                    .dedupeKey(todo.peopleId() + ":" + day)
                    .audience(NotifyAudience.C_ACCOUNT)
                    .peopleId(todo.peopleId())
                    .orgId(orgId)
                    .title("今日健康待办")
                    .body(body)
                    .linkPath("/home")
                    .payload(payload)
                    .onDuplicate(NotifyDuplicatePolicy.UPSERT_BODY_KEEP_READ)
                    .build());
        } catch (Exception ex) {
            log.warn(
                    "DAILY_HEALTH_TODO notify failed tenant={} people={} day={}: {}",
                    tenantId,
                    todo.peopleId(),
                    day,
                    ex.getMessage());
            return new NotifyPublishResult();
        }
    }

    static String buildBody(String name, DailyHealthTodoCandidate todo) {
        List<String> parts = new ArrayList<>();
        if (todo.planIncompleteFlag() && todo.planIncomplete() > 0) {
            parts.add("方案 " + todo.planIncomplete() + " 项未完成");
        } else if (todo.planIncompleteFlag()) {
            parts.add("方案打卡未完成");
        }
        if (todo.medIncompleteFlag()) {
            int pending = Math.max(0, todo.medDueDoses() - todo.medTakenDoses());
            if (pending > 0) {
                parts.add("用药 " + pending + " 次未打卡");
            } else {
                parts.add("用药未打卡");
            }
        }
        if (parts.isEmpty()) {
            return name + "今日还有健康待办未完成";
        }
        return name + "今日还有" + String.join("、", parts);
    }

    private Map<String, String> loadDisplayNames(List<String> peopleIds) {
        Map<String, String> names = new HashMap<>();
        for (String peopleId : peopleIds) {
            PeopleProfile profile = peopleProfileMapper.findById(peopleId);
            if (profile != null && StringUtils.hasText(profile.getDisplayName())) {
                names.put(peopleId, profile.getDisplayName().trim());
            }
        }
        return names;
    }
}
