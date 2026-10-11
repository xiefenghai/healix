package com.healix.core.observation.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.service.DictService;
import com.healix.core.observation.domain.LabReport;
import com.healix.core.observation.domain.LabResultItem;
import com.healix.core.observation.dto.LabReportViewDto;
import com.healix.core.observation.dto.LabReportViewDto.LabItemViewDto;
import com.healix.core.observation.enums.HealthDataSourceEnum;
import com.healix.core.observation.mapper.LabReportMapper;
import com.healix.core.observation.mapper.LabResultItemMapper;
import com.healix.core.observation.support.LabItemCodeMapper;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class LabReportService {

    private static final String OPERATOR_STAFF = "STAFF";
    private static final String OPERATOR_PEOPLE = "PEOPLE";

    private final LabReportMapper labReportMapper;
    private final LabResultItemMapper labResultItemMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;
    private final DictService dictService;
    private final LabItemCodeMapper labItemCodeMapper;

    public List<LabReportViewDto> list(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return labReportMapper.listByPeople(tenantId, peopleId).stream()
                .map(r -> toView(r, labResultItemMapper.listByReportId(r.getId())))
                .toList();
    }

    public List<LabReportViewDto> listForPatient(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return labReportMapper.listByPeople(tenantId, peopleId).stream()
                .map(r -> toView(r, labResultItemMapper.listByReportId(r.getId())))
                .toList();
    }

    public LabReportViewDto getForPatient(String tenantId, String peopleId, String reportId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        LabReport row = requireOwned(tenantId, peopleId, reportId);
        return toView(row, labResultItemMapper.listByReportId(reportId));
    }

    public LabReportViewDto get(String tenantId, String orgId, String peopleId, String reportId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        LabReport row = requireOwned(tenantId, peopleId, reportId);
        return toView(row, labResultItemMapper.listByReportId(reportId));
    }

    @Transactional
    public LabReportViewDto create(
            String tenantId, String orgId, String peopleId, String staffId, LabReportCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validateCommand(cmd);
        LabReport row = new LabReport();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setOrgId(orgId);
        row.setSource(HealthDataSourceEnum.resolveStaffWrite(cmd.source()));
        row.setCreatedByStaffId(staffId);
        row.setUpdatedByStaffId(staffId);
        applyHeader(row, cmd);
        EntityMeta.onCreate(row);
        labReportMapper.insert(row);
        List<LabResultItem> items = insertItems(row.getId(), cmd.items());
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.LAB.name(),
                row.getId(),
                0,
                1,
                orgId,
                "{}",
                toRevisionJson(row, items));
        return toView(row, items);
    }

    /** C 端：患者自行上报检验报告。 */
    @Transactional
    public LabReportViewDto createForPatient(String tenantId, String peopleId, LabReportCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        validateCommand(cmd);
        LabReport row = new LabReport();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setSource(HealthDataSourceEnum.resolvePatientWrite(cmd.source()));
        applyHeader(row, cmd);
        EntityMeta.onCreate(row);
        labReportMapper.insert(row);
        List<LabResultItem> items = insertItems(row.getId(), cmd.items());
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.LAB.name(),
                row.getId(),
                0,
                1,
                null,
                "{}",
                toRevisionJson(row, items));
        return toView(row, items);
    }

    /** C 端：用户修改自己上报的检验报告。 */
    @Transactional
    public LabReportViewDto updateForPatient(
            String tenantId, String peopleId, String reportId, LabReportCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        validateCommand(cmd);
        LabReport row = requireOwned(tenantId, peopleId, reportId);
        assertPatientOwned(row.getSource());
        List<LabResultItem> oldItems = labResultItemMapper.listByReportId(reportId);
        String oldJson = toRevisionJson(row, oldItems);
        applyHeader(row, cmd);
        if (StringUtils.hasText(cmd.source())) {
            row.setSource(HealthDataSourceEnum.resolvePatientWrite(cmd.source()));
        }
        EntityMeta.onUpdate(row);
        labReportMapper.update(row);
        labResultItemMapper.softDeleteByReportId(reportId);
        List<LabResultItem> items = insertItems(reportId, cmd.items());
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.LAB.name(),
                reportId,
                1,
                1,
                null,
                oldJson,
                toRevisionJson(row, items));
        return toView(row, items);
    }

    /** C 端：用户删除自己上报的检验报告。 */
    @Transactional
    public void deleteForPatient(String tenantId, String peopleId, String reportId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        LabReport row = requireOwned(tenantId, peopleId, reportId);
        assertPatientOwned(row.getSource());
        List<LabResultItem> items = labResultItemMapper.listByReportId(reportId);
        String snapshot = toRevisionJson(row, items);
        labResultItemMapper.softDeleteByReportId(reportId);
        labReportMapper.softDelete(reportId, null);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.LAB.name(),
                reportId,
                null,
                snapshot);
    }

    private void assertPatientOwned(String source) {
        if (!HealthDataSourceEnum.isPatientOwned(source)) {
            throw new BusinessException("仅可修改自己上报的数据");
        }
    }

    /** 整单替换：更新报告头，软删旧明细后写入新明细。 */
    @Transactional
    public LabReportViewDto update(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String reportId,
            LabReportCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validateCommand(cmd);
        LabReport row = requireOwned(tenantId, peopleId, reportId);
        List<LabResultItem> oldItems = labResultItemMapper.listByReportId(reportId);
        String oldJson = toRevisionJson(row, oldItems);
        applyHeader(row, cmd);
        row.setOrgId(orgId);
        row.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(row);
        labReportMapper.update(row);
        labResultItemMapper.softDeleteByReportId(reportId);
        List<LabResultItem> items = insertItems(reportId, cmd.items());
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.LAB.name(),
                reportId,
                1,
                1,
                orgId,
                oldJson,
                toRevisionJson(row, items));
        return toView(row, items);
    }

    @Transactional
    public void delete(String tenantId, String orgId, String peopleId, String staffId, String reportId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        LabReport row = requireOwned(tenantId, peopleId, reportId);
        List<LabResultItem> items = labResultItemMapper.listByReportId(reportId);
        String snapshot = toRevisionJson(row, items);
        labResultItemMapper.softDeleteByReportId(reportId);
        labReportMapper.softDelete(reportId, staffId);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.LAB.name(),
                reportId,
                orgId,
                snapshot);
    }

    private List<LabResultItem> insertItems(String reportId, List<LabItemCommand> cmds) {
        List<LabResultItem> out = new ArrayList<>();
        if (cmds == null) {
            return out;
        }
        for (LabItemCommand cmd : cmds) {
            if (cmd == null || !StringUtils.hasText(cmd.itemCode())) {
                continue;
            }
            String code = cmd.itemCode().trim();
            if (!labItemCodeMapper.isKnownCode(code)) {
                throw new BusinessException("检验项目不在系统目录中：" + code);
            }
            if (cmd.valueNum() == null && !StringUtils.hasText(cmd.valueText())) {
                continue;
            }
            LabResultItem item = new LabResultItem();
            item.setReportId(reportId);
            item.setItemCode(cmd.itemCode().trim());
            item.setItemName(resolveItemName(cmd));
            item.setValueNum(cmd.valueNum());
            item.setValueText(blankToNull(cmd.valueText()));
            DictDefaults defaults = dictDefaults(cmd.itemCode().trim());
            item.setUnit(StringUtils.hasText(cmd.unit()) ? cmd.unit().trim() : defaults.unit());
            item.setRefLow(cmd.refLow() != null ? cmd.refLow() : defaults.refLow());
            item.setRefHigh(cmd.refHigh() != null ? cmd.refHigh() : defaults.refHigh());
            item.setAbnormalFlag(resolveAbnormalFlag(cmd, item.getValueNum(), item.getRefLow(), item.getRefHigh()));
            EntityMeta.onCreate(item);
            labResultItemMapper.insert(item);
            out.add(item);
        }
        return out;
    }

    private String resolveItemName(LabItemCommand cmd) {
        if (StringUtils.hasText(cmd.itemName())) {
            return cmd.itemName().trim();
        }
        return dictService
                .listMerged(DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), "labItemCode")
                .stream()
                .filter(d -> cmd.itemCode().equals(d.getDictCode()))
                .map(DictItemDto::getDictCodeDesc)
                .findFirst()
                .orElse(cmd.itemCode());
    }

    private DictDefaults dictDefaults(String itemCode) {
        return dictService
                .listMerged(DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), "labItemCode")
                .stream()
                .filter(d -> itemCode.equals(d.getDictCode()))
                .findFirst()
                .map(d -> {
                    Map<?, ?> content = JsonUtils.fromJson(d.getContent(), Map.class);
                    if (content == null) {
                        return new DictDefaults(null, null, null);
                    }
                    return new DictDefaults(
                            asString(content.get("unit")),
                            asDecimal(content.get("refLow")),
                            asDecimal(content.get("refHigh")));
                })
                .orElse(new DictDefaults(null, null, null));
    }

    private String resolveAbnormalFlag(
            LabItemCommand cmd, BigDecimal valueNum, BigDecimal refLow, BigDecimal refHigh) {
        if (StringUtils.hasText(cmd.abnormalFlag())) {
            return cmd.abnormalFlag().trim().toUpperCase();
        }
        if (valueNum == null) {
            return "N";
        }
        if (refHigh != null && valueNum.compareTo(refHigh) > 0) {
            return "H";
        }
        if (refLow != null && valueNum.compareTo(refLow) < 0) {
            return "L";
        }
        return "N";
    }

    private void applyHeader(LabReport row, LabReportCommand cmd) {
        row.setSpecimenType(StringUtils.hasText(cmd.specimenType()) ? cmd.specimenType().trim() : "BLOOD");
        row.setSampledAt(cmd.sampledAt());
        row.setReportedAt(cmd.reportedAt() != null ? cmd.reportedAt() : LocalDateTime.now());
        row.setNote(blankToNull(cmd.note()));
    }

    private void validateCommand(LabReportCommand cmd) {
        if (cmd == null) {
            throw new BusinessException("检验报告不能为空");
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            throw new BusinessException("请至少填写一项检验结果");
        }
        for (LabItemCommand item : cmd.items()) {
            if (item == null || !StringUtils.hasText(item.itemCode())) {
                continue;
            }
            if (item.valueNum() == null && !StringUtils.hasText(item.valueText())) {
                continue;
            }
            if (!labItemCodeMapper.isKnownCode(item.itemCode().trim())) {
                throw new BusinessException("检验项目不在系统目录中：" + item.itemCode());
            }
        }
    }

    private LabReport requireOwned(String tenantId, String peopleId, String reportId) {
        LabReport row = labReportMapper.findById(reportId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException("检验报告不存在");
        }
        return row;
    }

    private LabReportViewDto toView(LabReport row, List<LabResultItem> items) {
        List<LabItemViewDto> itemViews = items.stream()
                .map(i -> new LabItemViewDto(
                        i.getId(),
                        i.getItemCode(),
                        i.getItemName(),
                        i.getValueNum(),
                        i.getValueText(),
                        i.getUnit(),
                        i.getRefLow(),
                        i.getRefHigh(),
                        i.getAbnormalFlag()))
                .toList();
        String source = HealthDataSourceEnum.normalize(row.getSource(), row.getCreatedByStaffId()).name();
        return new LabReportViewDto(
                row.getId(),
                row.getPeopleId(),
                row.getOrgId(),
                row.getSpecimenType(),
                row.getSampledAt(),
                row.getReportedAt(),
                source,
                row.getNote(),
                itemViews,
                row.getGmtCreated());
    }

    private String toRevisionJson(LabReport row, List<LabResultItem> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("specimenType", row.getSpecimenType());
        m.put("sampledAt", row.getSampledAt() == null ? null : row.getSampledAt().toString());
        m.put("reportedAt", row.getReportedAt() == null ? null : row.getReportedAt().toString());
        m.put("note", row.getNote());
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (LabResultItem i : items) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("itemCode", i.getItemCode());
            im.put("itemName", i.getItemName());
            im.put("valueNum", i.getValueNum());
            im.put("valueText", i.getValueText());
            im.put("unit", i.getUnit());
            im.put("abnormalFlag", i.getAbnormalFlag());
            itemMaps.add(im);
        }
        m.put("items", itemMaps);
        return JsonUtils.toJson(m);
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal asDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record DictDefaults(String unit, BigDecimal refLow, BigDecimal refHigh) {}

    public record LabReportCommand(
            String specimenType,
            LocalDateTime sampledAt,
            LocalDateTime reportedAt,
            String note,
            String source,
            List<LabItemCommand> items) {}

    public record LabItemCommand(
            String itemCode,
            String itemName,
            BigDecimal valueNum,
            String valueText,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String abnormalFlag) {}
}
