package com.healix.core.observation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.observation.domain.ExamReport;
import com.healix.core.observation.dto.ExamReportViewDto;
import com.healix.core.observation.enums.HealthDataSourceEnum;
import com.healix.core.observation.mapper.ExamReportMapper;
import com.healix.core.observation.support.ExamTypeCatalog;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ExamReportService {

    private static final String OPERATOR_STAFF = "STAFF";
    private static final String OPERATOR_PEOPLE = "PEOPLE";
    private final ExamReportMapper examReportMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;

    public List<ExamReportViewDto> list(String tenantId, String orgId, String peopleId, String examType) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return examReportMapper.listByPeople(tenantId, peopleId, blankToNull(examType)).stream()
                .map(this::toView)
                .toList();
    }

    public List<ExamReportViewDto> listForPatient(String tenantId, String peopleId, String examType) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return examReportMapper.listByPeople(tenantId, peopleId, blankToNull(examType)).stream()
                .map(this::toView)
                .toList();
    }

    public ExamReportViewDto getForPatient(String tenantId, String peopleId, String reportId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        ExamReport row = requireOwned(tenantId, peopleId, reportId);
        return toView(row);
    }

    public ExamReportViewDto get(String tenantId, String orgId, String peopleId, String examId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return toView(requireOwned(tenantId, peopleId, examId));
    }

    @Transactional
    public ExamReportViewDto create(
            String tenantId, String orgId, String peopleId, String staffId, ExamCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validate(cmd);
        ExamReport row = new ExamReport();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setOrgId(orgId);
        row.setSource(HealthDataSourceEnum.resolveExamStaffWrite());
        row.setCreatedByStaffId(staffId);
        row.setUpdatedByStaffId(staffId);
        apply(row, cmd);
        EntityMeta.onCreate(row);
        examReportMapper.insert(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.EXAM.name(),
                row.getId(),
                0,
                1,
                orgId,
                "{}",
                toRevisionJson(row));
        return toView(row);
    }

    /** C 端：患者自行上报检查报告（本期无 OCR）。 */
    @Transactional
    public ExamReportViewDto createForPatient(String tenantId, String peopleId, ExamCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        validate(cmd);
        ExamReport row = new ExamReport();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setSource(HealthDataSourceEnum.resolveExamPatientWrite());
        apply(row, cmd);
        EntityMeta.onCreate(row);
        examReportMapper.insert(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.EXAM.name(),
                row.getId(),
                0,
                1,
                null,
                "{}",
                toRevisionJson(row));
        return toView(row);
    }

    /** C 端：用户修改自己上报的检查报告。 */
    @Transactional
    public ExamReportViewDto updateForPatient(
            String tenantId, String peopleId, String examId, ExamCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        validate(cmd);
        ExamReport row = requireOwned(tenantId, peopleId, examId);
        assertPatientOwned(row.getSource());
        String oldJson = toRevisionJson(row);
        apply(row, cmd);
        EntityMeta.onUpdate(row);
        examReportMapper.update(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.EXAM.name(),
                examId,
                1,
                1,
                null,
                oldJson,
                toRevisionJson(row));
        return toView(row);
    }

    /** C 端：用户删除自己上报的检查报告。 */
    @Transactional
    public void deleteForPatient(String tenantId, String peopleId, String examId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        ExamReport row = requireOwned(tenantId, peopleId, examId);
        assertPatientOwned(row.getSource());
        String snapshot = toRevisionJson(row);
        examReportMapper.softDelete(examId, null);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.EXAM.name(),
                examId,
                null,
                snapshot);
    }

    private void assertPatientOwned(String source) {
        if (!HealthDataSourceEnum.isPatientOwned(source)) {
            throw new BusinessException("仅可修改自己上报的数据");
        }
    }

    @Transactional
    public ExamReportViewDto update(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String examId,
            ExamCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validate(cmd);
        ExamReport row = requireOwned(tenantId, peopleId, examId);
        String oldJson = toRevisionJson(row);
        apply(row, cmd);
        row.setOrgId(orgId);
        row.setUpdatedByStaffId(staffId);
        if (!StringUtils.hasText(row.getSource())) {
            row.setSource(HealthDataSourceEnum.resolveExamStaffWrite());
        }
        EntityMeta.onUpdate(row);
        examReportMapper.update(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.EXAM.name(),
                examId,
                1,
                1,
                orgId,
                oldJson,
                toRevisionJson(row));
        return toView(row);
    }

    @Transactional
    public void delete(String tenantId, String orgId, String peopleId, String staffId, String examId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        ExamReport row = requireOwned(tenantId, peopleId, examId);
        String snapshot = toRevisionJson(row);
        examReportMapper.softDelete(examId, staffId);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.EXAM.name(),
                examId,
                orgId,
                snapshot);
    }

    private void apply(ExamReport row, ExamCommand cmd) {
        row.setExamType(cmd.examType().trim());
        row.setExaminedAt(cmd.examinedAt() != null ? cmd.examinedAt() : LocalDateTime.now());
        row.setConclusion(blankToNull(cmd.conclusion()));
        row.setFindingsJson(cmd.findings() == null || cmd.findings().isEmpty() ? null : JsonUtils.toJson(cmd.findings()));
    }

    private void validate(ExamCommand cmd) {
        if (cmd == null || !StringUtils.hasText(cmd.examType())) {
            throw new BusinessException("检查类型不能为空");
        }
        if (!ExamTypeCatalog.isKnownCode(cmd.examType())) {
            throw new BusinessException("不支持的检查类型: " + cmd.examType());
        }
        if (!StringUtils.hasText(cmd.conclusion()) && (cmd.findings() == null || cmd.findings().isEmpty())) {
            throw new BusinessException("请填写结论或关键测量");
        }
    }

    private ExamReport requireOwned(String tenantId, String peopleId, String examId) {
        ExamReport row = examReportMapper.findById(examId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException("检查报告不存在");
        }
        return row;
    }

    private ExamReportViewDto toView(ExamReport row) {
        String source = HealthDataSourceEnum.normalize(row.getSource(), row.getCreatedByStaffId()).name();
        return new ExamReportViewDto(
                row.getId(),
                row.getPeopleId(),
                row.getOrgId(),
                row.getExamType(),
                row.getExaminedAt(),
                row.getConclusion(),
                parseFindings(row.getFindingsJson()),
                source,
                row.getGmtCreated());
    }

    private String toRevisionJson(ExamReport row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("examType", row.getExamType());
        m.put("examinedAt", row.getExaminedAt() == null ? null : row.getExaminedAt().toString());
        m.put("conclusion", row.getConclusion());
        m.put("findings", parseFindings(row.getFindingsJson()));
        m.put("source", row.getSource());
        return JsonUtils.toJson(m);
    }

    private static Map<String, Object> parseFindings(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        Map<String, Object> map = JsonUtils.fromJson(json, new TypeReference<>() {});
        return map == null ? Map.of() : map;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    public record ExamCommand(
            String examType, LocalDateTime examinedAt, String conclusion, Map<String, Object> findings) {}
}
