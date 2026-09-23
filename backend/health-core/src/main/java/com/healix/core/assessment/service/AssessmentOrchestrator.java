package com.healix.core.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentKind;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTriggerSource;
import com.healix.core.assessment.domain.PeopleAssessmentSnapshot;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.AvailableEngineDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.ChinaParPrepDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.PrepFieldDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto.GuidelineDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto.ItemDto;
import com.healix.core.assessment.dto.ChinaParRecordRequest;
import com.healix.core.assessment.engine.AssessmentEngine;
import com.healix.core.assessment.mapper.PeopleAssessmentSnapshotMapper;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentContextBuilder;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.CdrsSupplementalRisk;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.common.domain.EntityMeta;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AssessmentOrchestrator {

    private final List<AssessmentEngine> engines;
    private final AssessmentContextBuilder contextBuilder;
    private final PeopleAssessmentSnapshotMapper snapshotMapper;
    private final ArchiveAccessService archiveAccessService;
    private final AuditService auditService;

    public AssessmentOverviewDto overview(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return buildOverview(tenantId, peopleId);
    }

    /** C 端只读概览：当前就诊人的最新评估结果（不做员工档案权限校验）。 */
    public AssessmentOverviewDto overviewForPatient(String tenantId, String peopleId) {
        return buildOverview(tenantId, peopleId);
    }

    private AssessmentOverviewDto buildOverview(String tenantId, String peopleId) {
        AssessmentContext ctx = contextBuilder.build(tenantId, peopleId);

        AssessmentOverviewDto dto = new AssessmentOverviewDto();
        if (ctx.hasKnownDiabetes()) {
            String reason = knownDiseaseReason(
                    "糖尿病",
                    ctx.isHasDiabetesDiseaseArchive(),
                    ctx.isHasDiabetesPresentIllness());
            dto.setCdrsHiddenReason(reason + "，疾病风险等级评估（CDRS）不适用");
            dto.getNotices().add(reason + "：启用血糖控制分标（红/黄/绿），CDRS 发病风险评分不适用");
        } else if (ctx.getAgeYears() != null && (ctx.getAgeYears() < 20 || ctx.getAgeYears() > 74)) {
            dto.setCdrsHiddenReason("CDRS 适用于 20–74 岁人群");
        }
        if (ctx.hasKnownHypertension()) {
            String reason = knownDiseaseReason(
                    "高血压",
                    ctx.isHasHypertensionDiseaseArchive(),
                    ctx.isHasHypertensionPresentIllness());
            dto.getNotices().add(reason + "：高血压风险评估不适用；请结合血压监测与用药管理跟进");
        }
        if (ctx.hasKnownObesity()) {
            String reason = knownDiseaseReason(
                    "肥胖症",
                    ctx.isHasObesityDiseaseArchive(),
                    ctx.isHasObesityPresentIllness());
            dto.getNotices().add(reason + "：肥胖风险评估不适用；请以确诊后的分层干预与管理为主");
        } else if (ctx.getAgeYears() != null && ctx.getAgeYears() < 18) {
            dto.getNotices().add("肥胖筛查：儿童青少年需按年龄别/性别别 BMI 百分位判定，本期不套用成人 BMI 阈值");
        }

        for (AssessmentEngine engine : engines) {
            if (!engine.applicable(ctx)) {
                continue;
            }
            AvailableEngineDto a = new AvailableEngineDto();
            a.setEngineCode(engine.code());
            a.setEngineLabel(engineLabel(engine.code()));
            a.setKind(engine.kind().name());
            a.setKindLabel(engine.kind().displayName());
            a.setRulePackVersion(engine.rulePackVersion());
            a.setGuidelineName(engine.guideline().name());
            dto.getAvailableEngines().add(a);
        }

        for (PeopleAssessmentSnapshot row : snapshotMapper.listLatestByPeople(tenantId, peopleId)) {
            // China-PAR 已下线展示，历史快照仍保留但不进入最新结果卡
            if (AssessmentEngineCode.CHINA_PAR.name().equals(row.getEngineCode())) {
                continue;
            }
            dto.getLatest().add(toDto(row));
        }
        return dto;
    }

    @Transactional
    public AssessmentSnapshotDto recordChinaPar(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String accountId,
            ChinaParRecordRequest req) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        if (req == null || req.getTenYearRiskPercent() == null) {
            throw new BusinessException(400, "请填写官方工具给出的 10 年发病风险（%）");
        }
        BigDecimal ten = req.getTenYearRiskPercent();
        if (ten.compareTo(BigDecimal.ZERO) < 0 || ten.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(400, "10 年风险应在 0–100% 之间");
        }
        AssessmentContext ctx = contextBuilder.build(tenantId, peopleId);
        Integer age = req.getAgeYears() != null ? req.getAgeYears() : ctx.getAgeYears();
        if (age != null && age < 20) {
            throw new BusinessException(400, "China-PAR 适用于 20 岁及以上人群");
        }

        String level = chinaParTenYearLevel(ten);
        BigDecimal lifetime = req.getLifetimeRiskPercent();
        String lifetimeLevel = null;
        if (lifetime != null) {
            if (lifetime.compareTo(BigDecimal.ZERO) < 0 || lifetime.compareTo(new BigDecimal("100")) > 0) {
                throw new BusinessException(400, "终生风险应在 0–100% 之间");
            }
            lifetimeLevel = lifetime.compareTo(new BigDecimal("32.8")) >= 0 ? "HIGH" : "LOW";
        }

        Map<String, Object> input = new LinkedHashMap<>(ctx.toInputSnapshot());
        putIfNotNull(input, "urbanRural", req.getUrbanRural());
        putIfNotNull(input, "geoRegion", req.getGeoRegion());
        putIfNotNull(input, "onAntihypertensive", req.getOnAntihypertensive());
        putIfNotNull(input, "currentSmoker", req.getCurrentSmoker());
        putIfNotNull(input, "diabetes", req.getDiabetes() != null ? req.getDiabetes() : ctx.isHasDiabetesDiseaseArchive());
        putIfNotNull(
                input,
                "familyHistoryAscvd",
                req.getFamilyHistoryAscvd() != null
                        ? req.getFamilyHistoryAscvd()
                        : ctx.getFirstDegreeAscvdFamilyHistory());
        putIfNotNull(input, "waistCm", req.getWaistCm() != null ? req.getWaistCm() : ctx.getWaistCm());
        putIfNotNull(input, "tcMmolL", req.getTcMmolL() != null ? req.getTcMmolL() : mmolOrNull(ctx.getTc(), ctx.getTcUnit(), false));
        putIfNotNull(input, "hdlMmolL", req.getHdlMmolL() != null ? req.getHdlMmolL() : mmolOrNull(ctx.getHdlC(), ctx.getHdlUnit(), true));
        putIfNotNull(input, "sbp", req.getSbp() != null ? req.getSbp() : ctx.getSbp());
        putIfNotNull(input, "dbp", req.getDbp() != null ? req.getDbp() : ctx.getDbp());
        putIfNotNull(input, "ageYears", age);
        putIfNotNull(input, "gender", StringUtils.hasText(req.getGender()) ? req.getGender() : ctx.getGender());
        putIfNotNull(input, "note", req.getNote());
        input.put("source", "OFFICIAL_TOOL_RECORD");
        input.put("officialToolUrl", "https://www.cvdrisk.com.cn/ASCVD/Eval");

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("recordMode", true);
        extras.put("tenYearRiskPercent", ten);
        extras.put("lifetimeRiskPercent", lifetime);
        extras.put("lifetimeLevel", lifetimeLevel);
        extras.put("officialToolUrl", "https://www.cvdrisk.com.cn/ASCVD/Eval");

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put(
                "advice",
                "已录入官方 China-PAR 结果：10 年风险 "
                        + ten.stripTrailingZeros().toPlainString()
                        + "%（"
                        + levelLabel(AssessmentEngineCode.CHINA_PAR.name(), level)
                        + "）"
                        + (lifetime != null
                                ? "；终生风险 "
                                        + lifetime.stripTrailingZeros().toPlainString()
                                        + "%"
                                        + (lifetimeLevel != null
                                                ? "（" + ("HIGH".equals(lifetimeLevel) ? "高危" : "低危") + "）"
                                                : "")
                                : "")
                        + "。属疾病风险等级评估辅助记录，本地未复算系数；不构成诊断或治疗决策，请以官方工具与临床判断为准。");
        resultJson.put("items", List.of());
        resultJson.put("missingFields", List.of());
        resultJson.put("extras", extras);
        Map<String, Object> guideline = new LinkedHashMap<>();
        guideline.put("name", "中国心血管病风险评估和管理指南（China-PAR）");
        guideline.put("version", "2019");
        guideline.put("publishedYear", 2019);
        resultJson.put("guideline", guideline);

        PeopleAssessmentSnapshot row = new PeopleAssessmentSnapshot();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setKind(AssessmentKind.INCIDENT_RISK.name());
        row.setEngineCode(AssessmentEngineCode.CHINA_PAR.name());
        row.setDiseaseCode("ascvd");
        row.setRulePackVersion("CHINA_PAR-RECORD.1");
        row.setStatus(AssessmentStatus.COMPLETE.name());
        row.setLevel(level);
        row.setScore(null);
        row.setProbability(ten.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        row.setResultJson(JsonUtils.toJson(resultJson));
        row.setInputSnapshotJson(JsonUtils.toJson(input));
        row.setTriggerSource(AssessmentTriggerSource.MANUAL.name());
        row.setAssessedAt(LocalDateTime.now());
        row.setAssessedByStaffId(staffId);
        EntityMeta.onCreate(row);
        snapshotMapper.insert(row);

        auditService.record(
                PortalEnum.B.code(),
                accountId,
                "STAFF",
                tenantId,
                AuditActionEnum.PATIENT_ASSESSMENT_RUN.name(),
                "people_assessment_snapshot",
                peopleId,
                peopleId,
                AuditDetails.of(
                        "triggerSource",
                        AssessmentTriggerSource.MANUAL.name(),
                        "engines",
                        List.of(AssessmentEngineCode.CHINA_PAR.name()),
                        "mode",
                        "OFFICIAL_RECORD",
                        "staffId",
                        staffId));
        return toDto(row);
    }

    public PageResult<AssessmentSnapshotDto> history(
            String tenantId, String orgId, String peopleId, String engineCode, int page, int pageSize) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        int p = Math.max(1, page);
        int size = Math.min(100, Math.max(1, pageSize));
        int offset = (p - 1) * size;
        String engine = StringUtils.hasText(engineCode) ? engineCode.trim() : null;
        long total = snapshotMapper.countHistory(tenantId, peopleId, engine);
        List<AssessmentSnapshotDto> items = snapshotMapper
                .listHistory(tenantId, peopleId, engine, offset, size)
                .stream()
                .map(this::toDto)
                .toList();
        return new PageResult<>(total, items);
    }

    public AssessmentSnapshotDto detail(String tenantId, String orgId, String peopleId, String id) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return detailForPatient(tenantId, peopleId, id);
    }

    public AssessmentSnapshotDto detailForPatient(String tenantId, String peopleId, String id) {
        PeopleAssessmentSnapshot row = snapshotMapper.findById(id);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "评估记录不存在");
        }
        return toDto(row);
    }

    @Transactional
    public List<AssessmentSnapshotDto> runManual(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String accountId,
            String engineCode) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        AssessmentContext ctx = contextBuilder.build(tenantId, peopleId);
        List<AssessmentEngine> targets = resolveTargets(ctx, engineCode);
        if (targets.isEmpty()) {
            throw new BusinessException(400, "没有可运行的评估引擎");
        }

        List<AssessmentSnapshotDto> out = new ArrayList<>();
        List<String> ran = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (AssessmentEngine engine : targets) {
            AssessmentResult result = engine.evaluate(ctx);
            PeopleAssessmentSnapshot row = new PeopleAssessmentSnapshot();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setKind(engine.kind().name());
            row.setEngineCode(engine.code());
            row.setDiseaseCode(engine.diseaseCode());
            row.setRulePackVersion(engine.rulePackVersion());
            row.setStatus(result.getStatus().name());
            row.setLevel(result.getLevel());
            row.setScore(result.getScore());
            row.setProbability(result.getProbability());
            row.setResultJson(JsonUtils.toJson(result.toResultJson()));
            row.setInputSnapshotJson(JsonUtils.toJson(ctx.toInputSnapshot()));
            row.setTriggerSource(AssessmentTriggerSource.MANUAL.name());
            row.setAssessedAt(now);
            row.setAssessedByStaffId(staffId);
            EntityMeta.onCreate(row);
            snapshotMapper.insert(row);
            out.add(toDto(row));
            ran.add(engine.code());
        }

        auditService.record(
                PortalEnum.B.code(),
                accountId,
                "STAFF",
                tenantId,
                AuditActionEnum.PATIENT_ASSESSMENT_RUN.name(),
                "people_assessment_snapshot",
                peopleId,
                peopleId,
                AuditDetails.of(
                        "triggerSource",
                        AssessmentTriggerSource.MANUAL.name(),
                        "engines",
                        ran,
                        "staffId",
                        staffId));
        return out;
    }

    private List<AssessmentEngine> resolveTargets(AssessmentContext ctx, String engineCode) {
        Map<String, AssessmentEngine> byCode =
                engines.stream().collect(Collectors.toMap(AssessmentEngine::code, Function.identity(), (a, b) -> a));
        if (StringUtils.hasText(engineCode)) {
            if (AssessmentEngineCode.CHINA_PAR.name().equals(engineCode.trim())) {
                throw new BusinessException(400, "China-PAR 评估已下线");
            }
            AssessmentEngine engine = byCode.get(engineCode.trim());
            if (engine == null) {
                throw new BusinessException(400, "未知引擎: " + engineCode);
            }
            if (!engine.applicable(ctx)) {
                throw new BusinessException(400, "当前患者不适用该引擎: " + engineCode);
            }
            return List.of(engine);
        }
        List<AssessmentEngine> list = new ArrayList<>();
        for (AssessmentEngine engine : engines) {
            if (engine.applicable(ctx)) {
                list.add(engine);
            }
        }
        return list;
    }

    private AssessmentSnapshotDto toDto(PeopleAssessmentSnapshot row) {
        AssessmentSnapshotDto dto = new AssessmentSnapshotDto();
        dto.setId(row.getId());
        dto.setKind(row.getKind());
        dto.setKindLabel(AssessmentKind.displayNameOf(row.getKind()));
        dto.setEngineCode(row.getEngineCode());
        dto.setEngineLabel(engineLabel(row.getEngineCode()));
        dto.setDiseaseCode(row.getDiseaseCode());
        dto.setRulePackVersion(row.getRulePackVersion());
        dto.setStatus(row.getStatus());
        dto.setLevel(row.getLevel());
        dto.setLevelLabel(levelLabel(row.getEngineCode(), row.getLevel()));
        dto.setScore(row.getScore());
        dto.setProbability(row.getProbability());
        dto.setTriggerSource(row.getTriggerSource());
        dto.setAssessedAt(row.getAssessedAt());
        dto.setAssessedByStaffId(row.getAssessedByStaffId());

        Map<String, Object> result =
                JsonUtils.fromJson(row.getResultJson(), new TypeReference<Map<String, Object>>() {});
        if (result != null) {
            dto.setAdvice(stringVal(result.get("advice")));
            dto.setExtras(asMap(result.get("extras")));
            Object missing = result.get("missingFields");
            if (missing instanceof List<?> list) {
                dto.setMissingFields(list.stream().map(String::valueOf).toList());
            }
            Object items = result.get("items");
            if (items instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        ItemDto item = new ItemDto();
                        item.setCode(stringVal(m.get("code")));
                        item.setLabel(stringVal(m.get("label")));
                        item.setInputValue(stringVal(m.get("inputValue")));
                        Object pts = m.get("points");
                        if (pts instanceof Number n) {
                            item.setPoints(n.intValue());
                        }
                        item.setRationale(stringVal(m.get("rationale")));
                        dto.getItems().add(item);
                    }
                }
            }
            Object g = result.get("guideline");
            if (g instanceof Map<?, ?> gm) {
                GuidelineDto gd = new GuidelineDto();
                gd.setName(stringVal(gm.get("name")));
                gd.setVersion(stringVal(gm.get("version")));
                Object y = gm.get("publishedYear");
                if (y instanceof Number n) {
                    gd.setPublishedYear(n.intValue());
                }
                dto.setGuideline(gd);
            }
        }
        dto.setInputSnapshot(
                JsonUtils.fromJson(row.getInputSnapshotJson(), new TypeReference<Map<String, Object>>() {}));
        return dto;
    }

    private static String knownDiseaseReason(String diseaseLabel, boolean diseaseArchive, boolean presentIllness) {
        if (diseaseArchive && presentIllness) {
            return "已建立" + diseaseLabel + "病种档案，且基础档案勾选了现有疾病";
        }
        if (diseaseArchive) {
            return "已建立" + diseaseLabel + "病种档案";
        }
        return "基础档案「现有疾病」已勾选" + diseaseLabel;
    }

    private static String engineLabel(String code) {
        if (AssessmentEngineCode.CDRS.name().equals(code)) {
            return "中国糖尿病风险评分（CDRS）";
        }
        if (AssessmentEngineCode.OBESITY_SCREEN.name().equals(code)) {
            return "肥胖筛查（BMI/腰围）";
        }
        if (AssessmentEngineCode.HYPERTENSION_RISK.name().equals(code)) {
            return "高血压风险评估";
        }
        if (AssessmentEngineCode.DIABETES_CONTROL_LABEL.name().equals(code)) {
            return "糖尿病血糖控制分标";
        }
        if (AssessmentEngineCode.CHINA_PAR.name().equals(code)) {
            return "心脑血管风险（China-PAR·官方结果）";
        }
        return code;
    }

    private static String levelLabel(String engine, String level) {
        if (!StringUtils.hasText(level)) {
            return null;
        }
        if (AssessmentEngineCode.DIABETES_CONTROL_LABEL.name().equals(engine)) {
            return switch (level) {
                case "NONE" -> "无标";
                case "RED" -> "红标";
                case "YELLOW" -> "黄标";
                case "GREEN" -> "绿标";
                case "NEAR_GREEN" -> "准绿标";
                default -> level;
            };
        }
        if (AssessmentEngineCode.CDRS.name().equals(engine)
                || AssessmentEngineCode.CHINA_PAR.name().equals(engine)) {
            return switch (level) {
                case "LOW" -> "低危";
                case "MID" -> "中危";
                case "HIGH" -> "高危";
                default -> level;
            };
        }
        if (AssessmentEngineCode.HYPERTENSION_RISK.name().equals(engine)) {
            return switch (level) {
                case "NORMAL" -> "正常血压";
                case "PREHYPERTENSION" -> "高血压前期";
                case "GRADE_1" -> "1级高血压";
                case "GRADE_2" -> "2级高血压";
                case "GRADE_3" -> "3级高血压";
                default -> level;
            };
        }
        return switch (level) {
            case "UNDERWEIGHT" -> "偏瘦";
            case "NORMAL" -> "正常";
            case "OVERWEIGHT" -> "超重";
            case "MILD_OBESITY" -> "轻度肥胖";
            case "MODERATE_OBESITY" -> "中度肥胖";
            case "SEVERE_OBESITY" -> "重度肥胖";
            case "EXTREME_OBESITY" -> "极重度肥胖";
            default -> level;
        };
    }

    private ChinaParPrepDto buildChinaParPrep(AssessmentContext ctx, AssessmentSnapshotDto latest) {
        ChinaParPrepDto prep = new ChinaParPrepDto();
        Integer age = ctx.getAgeYears();
        if (age != null && age < 20) {
            prep.setApplicable(false);
            prep.setNotApplicableReason("China-PAR 适用于 20 岁及以上、尚无心血管病的个体");
        } else {
            prep.setApplicable(true);
        }
        prep.setLatest(latest);
        prep.getFields().add(field("GENDER", "性别", StringUtils.hasText(ctx.getGender()), genderLabel(ctx.getGender()), "档案基本信息"));
        prep.getFields().add(field("AGE", "年龄", age != null, age == null ? null : age + " 岁", "档案生日"));
        prep.getFields().add(field("WAIST", "腰围", ctx.getWaistCm() != null, ctx.getWaistCm() == null ? null : ctx.getWaistCm().toPlainString() + " cm", "健康数据"));
        prep.getFields().add(field("SBP", "收缩压", ctx.getSbp() != null, ctx.getSbp() == null ? null : ctx.getSbp().toPlainString() + " mmHg", "健康数据"));
        prep.getFields().add(field("DBP", "舒张压", ctx.getDbp() != null, ctx.getDbp() == null ? null : ctx.getDbp().toPlainString() + " mmHg", "健康数据"));
        BigDecimal tc = mmolOrNull(ctx.getTc(), ctx.getTcUnit(), false);
        BigDecimal hdl = mmolOrNull(ctx.getHdlC(), ctx.getHdlUnit(), true);
        prep.getFields().add(field("TC", "总胆固醇 TC", tc != null, tc == null ? null : tc.toPlainString() + " mmol/L", "检验"));
        prep.getFields().add(field("HDL", "HDL-C", hdl != null, hdl == null ? null : hdl.toPlainString() + " mmol/L", "检验"));
        prep.getFields().add(field("DM", "糖尿病", true, ctx.isHasDiabetesDiseaseArchive() ? "有（病种档案）" : "无病种档案", "病种档案"));
        boolean smokeReady = ctx.isSmokingCollected();
        prep.getFields().add(field(
                "SMOKE",
                "现在是否吸烟",
                smokeReady,
                smokeReady ? smokingLabel(ctx.getSmokingStatus()) : null,
                "生活方式"));
        boolean fhReady = ctx.isFamilyHistoryCollected();
        prep.getFields().add(field(
                "FH_ASCVD",
                "心血管病家族史",
                fhReady,
                fhReady
                        ? (Boolean.TRUE.equals(ctx.getFirstDegreeAscvdFamilyHistory()) ? "有（一级亲属）" : "无/未标 ASCVD")
                        : null,
                "家族史（冠心病/早发心梗/脑卒中）"));
        prep.getFields().add(field("URBAN", "城乡（城市/农村）", false, null, "录入时补填"));
        prep.getFields().add(field("REGION", "南北（长江为界）", false, null, "录入时补填"));
        prep.getFields().add(field("DRUG", "是否服用降压药", false, null, "录入时补填"));
        return prep;
    }

    private static PrepFieldDto field(String code, String label, boolean ready, String value, String hint) {
        PrepFieldDto f = new PrepFieldDto();
        f.setCode(code);
        f.setLabel(label);
        f.setReady(ready);
        f.setValue(value);
        f.setHint(hint);
        return f;
    }

    private static String chinaParTenYearLevel(BigDecimal percent) {
        if (percent.compareTo(new BigDecimal("10")) >= 0) {
            return "HIGH";
        }
        if (percent.compareTo(new BigDecimal("5")) >= 0) {
            return "MID";
        }
        return "LOW";
    }

    private static BigDecimal mmolOrNull(BigDecimal value, String unit, boolean hdl) {
        return CdrsSupplementalRisk.toMmol(value, unit, hdl);
    }

    private static void putIfNotNull(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private static String genderLabel(String gender) {
        if ("MALE".equalsIgnoreCase(gender)) {
            return "男";
        }
        if ("FEMALE".equalsIgnoreCase(gender)) {
            return "女";
        }
        return gender;
    }

    private static String smokingLabel(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        return switch (status.toUpperCase()) {
            case "CURRENT", "OCCASIONAL" -> "是（当前/偶尔）";
            case "NEVER" -> "否（从不）";
            case "FORMER" -> "否（已戒）";
            default -> status;
        };
    }

    private static String stringVal(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return null;
    }
}
