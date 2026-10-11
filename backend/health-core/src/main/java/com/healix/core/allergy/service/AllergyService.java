package com.healix.core.allergy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.domain.PeopleAllergy;
import com.healix.core.allergy.domain.PeopleAllergyProfile;
import com.healix.core.allergy.dto.AllergyCatalogDto;
import com.healix.core.allergy.dto.AllergyItemDto;
import com.healix.core.allergy.dto.AllergySummaryDto;
import com.healix.core.allergy.enums.AllergyCategoryEnum;
import com.healix.core.allergy.enums.AllergyClinicalStatusEnum;
import com.healix.core.allergy.enums.AllergyReactionEnum;
import com.healix.core.allergy.enums.AllergySeverityEnum;
import com.healix.core.allergy.enums.AllergySourceEnum;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.allergy.enums.AllergyTypeEnum;
import com.healix.core.allergy.enums.AllergyVerificationEnum;
import com.healix.core.allergy.mapper.PeopleAllergyMapper;
import com.healix.core.allergy.mapper.PeopleAllergyProfileMapper;
import com.healix.core.allergy.support.AllergenCatalog;
import com.healix.core.allergy.support.AllergenCatalog.Allergen;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 过敏史维护（B/C 双端）。
 *
 * <p>患者自填条目为「患者自述」，医护确认后患者端只读；患者只能改删自己填且未确认的条目。
 * 采集状态见 {@link AllergyQueryService#resolveStatus}：有生效条目即「有过敏」，「无已知过敏」须显式设置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllergyService {

    private static final String OPERATOR_STAFF = "STAFF";
    private static final String OPERATOR_PEOPLE = "PEOPLE";
    private static final String OPERATOR_SYSTEM = "SYSTEM";
    private static final String STATUS_BIZ_KEY = "status";
    private static final int MAX_ITEMS = 50;

    private final PeopleAllergyMapper allergyMapper;
    private final PeopleAllergyProfileMapper profileMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;

    public record AllergyCommand(
            String category,
            String allergenCode,
            String allergenName,
            String allergyType,
            List<String> reactions,
            String reactionNote,
            String severity,
            String clinicalStatus,
            String verification,
            String onsetText,
            LocalDate lastOccurrence,
            String note) {}

    /** 操作人；orgId 仅员工有。 */
    public record Actor(String type, String id, String orgId) {
        public static Actor staff(String staffId, String orgId) {
            return new Actor(OPERATOR_STAFF, staffId, orgId);
        }

        public static Actor patient(String peopleId) {
            return new Actor(OPERATOR_PEOPLE, peopleId, null);
        }

        boolean isStaff() {
            return OPERATOR_STAFF.equals(type);
        }
    }

    public AllergyCatalogDto catalog() {
        return new AllergyCatalogDto(
                Arrays.stream(AllergyCategoryEnum.values())
                        .map(c -> new AllergyCatalogDto.Option(c.name(), c.label()))
                        .toList(),
                AllergenCatalog.all().stream()
                        .map(a -> new AllergyCatalogDto.AllergenOption(
                                a.code(), a.category().name(), a.label(), a.aliases(), a.defaultType().name()))
                        .toList(),
                Arrays.stream(AllergyReactionEnum.values())
                        .map(r -> new AllergyCatalogDto.ReactionOption(r.name(), r.label(), r.highRisk()))
                        .toList(),
                Arrays.stream(AllergySeverityEnum.values())
                        .map(s -> new AllergyCatalogDto.Option(s.name(), s.label()))
                        .toList(),
                Arrays.stream(AllergyTypeEnum.values())
                        .map(t -> new AllergyCatalogDto.Option(t.name(), t.label()))
                        .toList());
    }

    // ---------- 查询 ----------

    public AllergySummaryDto summaryForStaff(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return summary(tenantId, peopleId);
    }

    public AllergySummaryDto summaryForPatient(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return summary(tenantId, peopleId);
    }

    private AllergySummaryDto summary(String tenantId, String peopleId) {
        List<PeopleAllergy> rows = allergyMapper.listByPeople(tenantId, peopleId);
        PeopleAllergyProfile profile = profileMapper.findByPeople(tenantId, peopleId);
        AllergyStatusEnum status = AllergyQueryService.resolveStatus(profile, rows);
        List<PeopleAllergy> effective = rows.stream()
                .filter(AllergyQueryService::isEffective)
                .sorted(Comparator.comparing((PeopleAllergy r) -> !Integer.valueOf(1).equals(r.getHighRisk())))
                .toList();
        return new AllergySummaryDto(
                status.name(),
                status.label(),
                profile == null ? null : profile.getReviewedAt(),
                profile == null ? null : profile.getReviewedByStaffId(),
                effective.stream().anyMatch(r -> Integer.valueOf(1).equals(r.getHighRisk())),
                (int) effective.stream()
                        .filter(r -> AllergyVerificationEnum.UNCONFIRMED.name().equals(r.getVerification()))
                        .count(),
                effective.stream().map(PeopleAllergy::getAllergenName).distinct().toList(),
                rows.stream().map(AllergyService::toDto).toList());
    }

    // ---------- 员工 ----------

    @Transactional
    public AllergyItemDto createByStaff(String tenantId, String orgId, String peopleId, String staffId, AllergyCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return create(tenantId, peopleId, Actor.staff(staffId, orgId), cmd);
    }

    @Transactional
    public AllergyItemDto updateByStaff(
            String tenantId, String orgId, String peopleId, String staffId, String allergyId, AllergyCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return update(tenantId, peopleId, Actor.staff(staffId, orgId), allergyId, cmd);
    }

    /** 确认（CONFIRMED）或排除（REFUTED）一条过敏史，常用于审核患者自述。 */
    @Transactional
    public AllergyItemDto verify(
            String tenantId, String orgId, String peopleId, String staffId, String allergyId, String verification) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        AllergyVerificationEnum v = AllergyVerificationEnum.parse(verification, null);
        if (v == null || v == AllergyVerificationEnum.UNCONFIRMED) {
            throw new BusinessException("请选择确认或排除");
        }
        PeopleAllergy row = requireOwned(tenantId, peopleId, allergyId);
        String oldJson = toRevisionJson(row);
        row.setVerification(v.name());
        row.setVerifiedByStaffId(staffId);
        row.setVerifiedAt(LocalDateTime.now());
        touch(row, Actor.staff(staffId, orgId));
        allergyMapper.update(row);
        recordChange(tenantId, peopleId, Actor.staff(staffId, orgId), row.getId(), 1, oldJson, toRevisionJson(row));
        syncProfile(tenantId, peopleId, Actor.staff(staffId, orgId));
        return toDto(row);
    }

    @Transactional
    public void deleteByStaff(String tenantId, String orgId, String peopleId, String staffId, String allergyId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        delete(tenantId, peopleId, Actor.staff(staffId, orgId), allergyId);
    }

    /** 设置「无已知过敏」或退回「未询问」；同时记为医护已核对。 */
    @Transactional
    public AllergySummaryDto setStatusByStaff(String tenantId, String orgId, String peopleId, String staffId, String status) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        setStatus(tenantId, peopleId, Actor.staff(staffId, orgId), status);
        return summary(tenantId, peopleId);
    }

    /** 医护核对过敏史（临床「过敏史已核对」），不改变条目。 */
    @Transactional
    public AllergySummaryDto markReviewed(String tenantId, String orgId, String peopleId, String staffId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleAllergyProfile profile = ensureProfile(tenantId, peopleId, Actor.staff(staffId, orgId));
        profile.setReviewedAt(LocalDateTime.now());
        profile.setReviewedByStaffId(staffId);
        EntityMeta.onUpdate(profile);
        profileMapper.update(profile);
        return summary(tenantId, peopleId);
    }

    // ---------- 患者 ----------

    @Transactional
    public AllergyItemDto createByPatient(String tenantId, String peopleId, AllergyCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return create(tenantId, peopleId, Actor.patient(peopleId), cmd);
    }

    @Transactional
    public AllergyItemDto updateByPatient(String tenantId, String peopleId, String allergyId, AllergyCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        assertPatientEditable(requireOwned(tenantId, peopleId, allergyId));
        return update(tenantId, peopleId, Actor.patient(peopleId), allergyId, cmd);
    }

    @Transactional
    public void deleteByPatient(String tenantId, String peopleId, String allergyId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        assertPatientEditable(requireOwned(tenantId, peopleId, allergyId));
        delete(tenantId, peopleId, Actor.patient(peopleId), allergyId);
    }

    @Transactional
    public AllergySummaryDto setStatusByPatient(String tenantId, String peopleId, String status) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        setStatus(tenantId, peopleId, Actor.patient(peopleId), status);
        return summary(tenantId, peopleId);
    }

    // ---------- 迁移 ----------

    /**
     * 把旧版基础档案「既往史 → 过敏史」结构化条目导入过敏史表。已建立过敏史状态的患者跳过，可重复执行。
     *
     * @return 导入的患者数
     */
    @Transactional
    public int importLegacyPastHistory() {
        int patients = 0;
        for (PeopleBasicArchive archive : allergyMapper.listLegacyArchives()) {
            JsonNode list = JsonUtils.readTree(archive.getContentJson()).path("pastHistoryItems").path("allergies");
            if (!list.isArray() || list.isEmpty()) {
                continue;
            }
            Actor system = new Actor(OPERATOR_SYSTEM, null, null);
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode n : list) {
                String name = trimToNull(n.path("allergen").asText(""), 64);
                if (name == null || !seen.add(name)) {
                    continue;
                }
                AllergyCategoryEnum category = parseLegacyCategory(n.path("category").asText(""));
                Optional<Allergen> matched = AllergenCatalog.matchName(name).filter(a -> a.category() == category);
                PeopleAllergy row = new PeopleAllergy();
                row.setTenantId(archive.getTenantId());
                row.setPeopleId(archive.getPeopleId());
                row.setCategory(category.name());
                row.setAllergenCode(matched.map(Allergen::code).orElse(null));
                row.setAllergenName(name);
                row.setAllergyType(matched.map(a -> a.defaultType().name()).orElse(AllergyTypeEnum.ALLERGY.name()));
                row.setReactionsJson("[]");
                row.setReactionNote(trimToNull(n.path("reaction").asText(""), 128));
                row.setSeverity(AllergySeverityEnum.UNKNOWN.name());
                row.setHighRisk(0);
                row.setClinicalStatus(AllergyClinicalStatusEnum.ACTIVE.name());
                row.setVerification(AllergyVerificationEnum.CONFIRMED.name());
                row.setSource(AllergySourceEnum.IMPORT.name());
                row.setCreatedByType(system.type());
                touch(row, system);
                EntityMeta.onCreate(row);
                allergyMapper.insert(row);
            }
            syncProfile(archive.getTenantId(), archive.getPeopleId(), system);
            patients++;
        }
        return patients;
    }

    // ---------- 内部 ----------

    private AllergyItemDto create(String tenantId, String peopleId, Actor actor, AllergyCommand cmd) {
        List<PeopleAllergy> existing = allergyMapper.listByPeople(tenantId, peopleId);
        if (existing.size() >= MAX_ITEMS) {
            throw new BusinessException("过敏史条目过多，请先整理");
        }
        PeopleAllergy row = new PeopleAllergy();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        apply(row, cmd, actor, true);
        assertNoDuplicate(existing, row, null);
        row.setSource(actor.isStaff() ? AllergySourceEnum.STAFF.name() : AllergySourceEnum.SELF.name());
        row.setCreatedByType(actor.type());
        row.setCreatedById(actor.id());
        touch(row, actor);
        EntityMeta.onCreate(row);
        allergyMapper.insert(row);
        recordChange(tenantId, peopleId, actor, row.getId(), 0, "{}", toRevisionJson(row));
        syncProfile(tenantId, peopleId, actor);
        return toDto(row);
    }

    private AllergyItemDto update(String tenantId, String peopleId, Actor actor, String allergyId, AllergyCommand cmd) {
        PeopleAllergy row = requireOwned(tenantId, peopleId, allergyId);
        String oldJson = toRevisionJson(row);
        apply(row, cmd, actor, false);
        assertNoDuplicate(allergyMapper.listByPeople(tenantId, peopleId), row, allergyId);
        touch(row, actor);
        allergyMapper.update(row);
        recordChange(tenantId, peopleId, actor, allergyId, 1, oldJson, toRevisionJson(row));
        syncProfile(tenantId, peopleId, actor);
        return toDto(row);
    }

    private void delete(String tenantId, String peopleId, Actor actor, String allergyId) {
        PeopleAllergy row = requireOwned(tenantId, peopleId, allergyId);
        allergyMapper.softDelete(allergyId);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                actor.type(),
                actor.id(),
                RevisionBizTypeEnum.ALLERGY.name(),
                allergyId,
                actor.orgId(),
                toRevisionJson(row));
        syncProfile(tenantId, peopleId, actor);
    }

    private void setStatus(String tenantId, String peopleId, Actor actor, String status) {
        AllergyStatusEnum target;
        try {
            target = AllergyStatusEnum.valueOf(String.valueOf(status).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的过敏史状态");
        }
        if (target == AllergyStatusEnum.HAS) {
            throw new BusinessException("请直接添加过敏条目");
        }
        List<PeopleAllergy> rows = allergyMapper.listByPeople(tenantId, peopleId);
        if (target == AllergyStatusEnum.NKA && rows.stream().anyMatch(AllergyQueryService::isEffective)) {
            throw new BusinessException("仍有生效的过敏条目，请先删除或标记为已消失/已排除");
        }
        PeopleAllergyProfile profile = ensureProfile(tenantId, peopleId, actor);
        String before = profile.getStatus();
        profile.setStatus(target.name());
        profile.setUpdatedByType(actor.type());
        profile.setUpdatedById(actor.id());
        if (actor.isStaff()) {
            profile.setReviewedAt(LocalDateTime.now());
            profile.setReviewedByStaffId(actor.id());
        }
        EntityMeta.onUpdate(profile);
        profileMapper.update(profile);
        if (!target.name().equals(before)) {
            recordChange(tenantId, peopleId, actor, STATUS_BIZ_KEY, 1,
                    statusJson(before), statusJson(target.name()));
        }
    }

    /** 条目变化后同步采集状态：有生效条目 → HAS；HAS 下条目清空 → UNKNOWN；显式 NKA 保持。 */
    private void syncProfile(String tenantId, String peopleId, Actor actor) {
        List<PeopleAllergy> rows = allergyMapper.listByPeople(tenantId, peopleId);
        PeopleAllergyProfile profile = ensureProfile(tenantId, peopleId, actor);
        boolean hasEffective = rows.stream().anyMatch(AllergyQueryService::isEffective);
        String next;
        if (hasEffective) {
            next = AllergyStatusEnum.HAS.name();
        } else if (AllergyStatusEnum.NKA.name().equals(profile.getStatus())) {
            next = AllergyStatusEnum.NKA.name();
        } else {
            next = AllergyStatusEnum.UNKNOWN.name();
        }
        if (!next.equals(profile.getStatus())) {
            profile.setStatus(next);
            profile.setUpdatedByType(actor.type());
            profile.setUpdatedById(actor.id());
            EntityMeta.onUpdate(profile);
            profileMapper.update(profile);
        }
    }

    private PeopleAllergyProfile ensureProfile(String tenantId, String peopleId, Actor actor) {
        PeopleAllergyProfile profile = profileMapper.findByPeople(tenantId, peopleId);
        if (profile != null) {
            return profile;
        }
        profile = new PeopleAllergyProfile();
        profile.setTenantId(tenantId);
        profile.setPeopleId(peopleId);
        profile.setStatus(AllergyStatusEnum.UNKNOWN.name());
        profile.setUpdatedByType(actor.type());
        profile.setUpdatedById(actor.id());
        EntityMeta.onCreate(profile);
        profileMapper.insert(profile);
        return profile;
    }

    private void apply(PeopleAllergy row, AllergyCommand cmd, Actor actor, boolean creating) {
        if (cmd == null) {
            throw new BusinessException("请求不能为空");
        }
        AllergyCategoryEnum category = AllergyCategoryEnum.parse(cmd.category());
        Optional<Allergen> catalogItem = AllergenCatalog.find(cmd.allergenCode());
        if (StringUtils.hasText(cmd.allergenCode()) && catalogItem.isEmpty()) {
            throw new BusinessException("无效的过敏原编码");
        }
        String name = trimToNull(cmd.allergenName(), 64);
        if (catalogItem.isEmpty() && name != null) {
            catalogItem = AllergenCatalog.matchName(name).filter(a -> a.category() == category);
        }
        if (catalogItem.isPresent()) {
            Allergen a = catalogItem.get();
            row.setCategory(a.category().name());
            row.setAllergenCode(a.code());
            row.setAllergenName(name != null ? name : a.label());
        } else {
            if (name == null) {
                throw new BusinessException("请填写过敏原");
            }
            row.setCategory(category.name());
            row.setAllergenCode(null);
            row.setAllergenName(name);
        }
        AllergyTypeEnum type = StringUtils.hasText(cmd.allergyType())
                ? AllergyTypeEnum.parse(cmd.allergyType())
                : catalogItem.map(Allergen::defaultType).orElse(AllergyTypeEnum.ALLERGY);
        row.setAllergyType(type.name());

        List<String> reactions = new ArrayList<>();
        boolean reactionHighRisk = false;
        if (cmd.reactions() != null) {
            for (String code : cmd.reactions()) {
                AllergyReactionEnum r = AllergyReactionEnum.parseOrNull(code);
                if (r != null && !reactions.contains(r.name())) {
                    reactions.add(r.name());
                    reactionHighRisk |= r.highRisk();
                }
            }
        }
        row.setReactionsJson(JsonUtils.toJson(reactions));
        row.setReactionNote(trimToNull(cmd.reactionNote(), 128));
        AllergySeverityEnum severity = AllergySeverityEnum.parse(cmd.severity());
        row.setSeverity(severity.name());
        row.setHighRisk(severity == AllergySeverityEnum.SEVERE || reactionHighRisk ? 1 : 0);
        row.setClinicalStatus(AllergyClinicalStatusEnum.parse(cmd.clinicalStatus()).name());
        row.setOnsetText(trimToNull(cmd.onsetText(), 32));
        if (cmd.lastOccurrence() != null && cmd.lastOccurrence().isAfter(LocalDate.now())) {
            throw new BusinessException("最近发作日期不能晚于今天");
        }
        row.setLastOccurrence(cmd.lastOccurrence());
        row.setNote(trimToNull(cmd.note(), 200));

        if (actor.isStaff()) {
            AllergyVerificationEnum fallback = creating
                    ? AllergyVerificationEnum.CONFIRMED
                    : AllergyVerificationEnum.parse(row.getVerification(), AllergyVerificationEnum.CONFIRMED);
            AllergyVerificationEnum v = AllergyVerificationEnum.parse(cmd.verification(), fallback);
            if (!v.name().equals(row.getVerification())) {
                row.setVerification(v.name());
                if (v == AllergyVerificationEnum.UNCONFIRMED) {
                    row.setVerifiedByStaffId(null);
                    row.setVerifiedAt(null);
                } else {
                    row.setVerifiedByStaffId(actor.id());
                    row.setVerifiedAt(LocalDateTime.now());
                }
            }
        } else {
            row.setVerification(AllergyVerificationEnum.UNCONFIRMED.name());
        }
    }

    private static void assertNoDuplicate(List<PeopleAllergy> existing, PeopleAllergy row, String selfId) {
        for (PeopleAllergy other : existing) {
            if (other.getId().equals(selfId)) {
                continue;
            }
            boolean sameCode = row.getAllergenCode() != null && row.getAllergenCode().equals(other.getAllergenCode());
            boolean sameName = row.getAllergenName().equals(other.getAllergenName());
            if (sameCode || sameName) {
                throw new BusinessException("已存在相同过敏原：" + other.getAllergenName());
            }
        }
    }

    private static void assertPatientEditable(PeopleAllergy row) {
        if (!editableByPatient(row)) {
            throw new BusinessException(403, "该条目已由医护确认，如需修改请联系健管师");
        }
    }

    private static boolean editableByPatient(PeopleAllergy row) {
        return AllergySourceEnum.SELF.name().equals(row.getSource())
                && AllergyVerificationEnum.UNCONFIRMED.name().equals(row.getVerification());
    }

    private PeopleAllergy requireOwned(String tenantId, String peopleId, String allergyId) {
        PeopleAllergy row = allergyMapper.findById(allergyId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "过敏条目不存在");
        }
        return row;
    }

    private static void touch(PeopleAllergy row, Actor actor) {
        row.setUpdatedByType(actor.type());
        row.setUpdatedById(actor.id());
        EntityMeta.onUpdate(row);
    }

    private void recordChange(
            String tenantId, String peopleId, Actor actor, String bizKey, int versionBefore, String oldJson, String newJson) {
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                actor.type(),
                actor.id() == null ? OPERATOR_SYSTEM : actor.id(),
                RevisionBizTypeEnum.ALLERGY.name(),
                bizKey,
                versionBefore,
                1,
                actor.orgId(),
                oldJson,
                newJson);
    }

    private static String statusJson(String status) {
        ObjectNode o = JsonUtils.emptyObject();
        if (status != null) {
            o.put("allergyStatus", status);
        }
        return JsonUtils.toJson(o);
    }

    private static String toRevisionJson(PeopleAllergy row) {
        ObjectNode o = JsonUtils.emptyObject();
        o.put("category", row.getCategory());
        o.put("allergenName", row.getAllergenName());
        o.put("allergyType", row.getAllergyType());
        ArrayNode reactions = o.putArray("reactions");
        AllergyQueryService.reactionCodes(row.getReactionsJson()).forEach(reactions::add);
        o.put("reactionNote", row.getReactionNote());
        o.put("severity", row.getSeverity());
        o.put("clinicalStatus", row.getClinicalStatus());
        o.put("verification", row.getVerification());
        o.put("onsetText", row.getOnsetText());
        o.put("lastOccurrence", row.getLastOccurrence() == null ? null : row.getLastOccurrence().toString());
        o.put("note", row.getNote());
        return JsonUtils.toJson(o);
    }

    static AllergyItemDto toDto(PeopleAllergy row) {
        List<String> reactions = AllergyQueryService.reactionCodes(row.getReactionsJson());
        return new AllergyItemDto(
                row.getId(),
                row.getCategory(),
                labelOf(AllergyCategoryEnum.class, row.getCategory()),
                row.getAllergenCode(),
                row.getAllergenName(),
                row.getAllergyType(),
                labelOf(AllergyTypeEnum.class, row.getAllergyType()),
                reactions,
                AllergyQueryService.reactionLabels(row.getReactionsJson()),
                row.getReactionNote(),
                row.getSeverity(),
                labelOf(AllergySeverityEnum.class, row.getSeverity()),
                Integer.valueOf(1).equals(row.getHighRisk()),
                row.getClinicalStatus(),
                row.getVerification(),
                labelOf(AllergyVerificationEnum.class, row.getVerification()),
                row.getOnsetText(),
                row.getLastOccurrence(),
                row.getSource(),
                row.getNote(),
                row.getVerifiedAt(),
                row.getGmtCreated(),
                row.getGmtModified(),
                AllergyQueryService.isEffective(row),
                editableByPatient(row));
    }

    private static <E extends Enum<E>> String labelOf(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            Enum<E> e = Enum.valueOf(type, value);
            return switch (e) {
                case AllergyCategoryEnum c -> c.label();
                case AllergyTypeEnum t -> t.label();
                case AllergySeverityEnum s -> s.label();
                case AllergyVerificationEnum v -> v.label();
                default -> value;
            };
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static AllergyCategoryEnum parseLegacyCategory(String raw) {
        try {
            return AllergyCategoryEnum.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return AllergyCategoryEnum.OTHER;
        }
    }

    private static String trimToNull(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }
}
