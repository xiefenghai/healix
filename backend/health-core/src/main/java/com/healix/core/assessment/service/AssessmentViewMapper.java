package com.healix.core.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.domain.PeopleAssessmentSnapshot;
import com.healix.core.assessment.dto.AssessmentRuleSheetDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto.GuidelineDto;
import com.healix.core.assessment.engine.AssessmentEngine;
import com.healix.core.assessment.engine.AssessmentEngine.LevelDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 快照 → 展示 DTO（等级名称、颜色全部取自引擎声明）。 */
@Component
@RequiredArgsConstructor
public class AssessmentViewMapper {

    /** 重构前快照的等级名称（引擎已改版，原生等级不再声明）。 */
    private static final Map<String, String> LEGACY_LEVELS = Map.ofEntries(
            Map.entry("NORMAL", "正常"),
            Map.entry("PREHYPERTENSION", "高血压前期"),
            Map.entry("GRADE_1", "1级高血压"),
            Map.entry("GRADE_2", "2级高血压"),
            Map.entry("GRADE_3", "3级高血压"),
            Map.entry("UNDERWEIGHT", "偏瘦"),
            Map.entry("OVERWEIGHT", "超重"),
            Map.entry("MILD_OBESITY", "轻度肥胖"),
            Map.entry("MODERATE_OBESITY", "中度肥胖"),
            Map.entry("SEVERE_OBESITY", "重度肥胖"),
            Map.entry("EXTREME_OBESITY", "极重度肥胖"),
            Map.entry("LOW", "低危"),
            Map.entry("MID", "中危"),
            Map.entry("HIGH", "高危"));

    private final AssessmentEngineRegistry registry;

    public static String tone(AssessmentTier tier) {
        return tier == null ? "info" : tier.tone();
    }

    public static String triggerLabel(String source) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case "MANUAL" -> "手动评估";
            case "AUTO" -> "自动重算";
            case "QUESTIONNAIRE" -> "问卷提交";
            case "JOB" -> "定时任务";
            default -> source;
        };
    }

    public AssessmentSnapshotDto toDto(PeopleAssessmentSnapshot row) {
        AssessmentEngine engine = registry.get(row.getEngineCode());
        AssessmentSnapshotDto dto = new AssessmentSnapshotDto();
        dto.setId(row.getId());
        dto.setCategory(row.getKind());
        dto.setCategoryLabel(AssessmentCategory.labelOf(row.getKind()));
        dto.setEngineCode(row.getEngineCode());
        dto.setEngineLabel(engine == null ? AssessmentEngineCode.legacyLabel(row.getEngineCode()) : engine.label());
        dto.setDiseaseCode(row.getDiseaseCode());
        AssessmentDisease disease = AssessmentDisease.ofCode(row.getDiseaseCode());
        dto.setDiseaseLabel(disease == null ? row.getDiseaseCode() : disease.label());
        dto.setRulePackVersion(row.getRulePackVersion());
        dto.setProvisional(row.getRulePackVersion() != null && row.getRulePackVersion().endsWith("PROVISIONAL"));
        dto.setStatus(row.getStatus());
        dto.setLevel(row.getLevel());
        LevelDef def = engine == null ? null : engine.levelDef(row.getLevel());
        dto.setLevelLabel(def != null ? def.label() : row.getLevel() == null ? null : LEGACY_LEVELS.getOrDefault(row.getLevel(), row.getLevel()));
        AssessmentTier tier = AssessmentTier.parse(row.getTier());
        dto.setTier(tier == null ? null : tier.name());
        dto.setTierLabel(tier == null ? null : tier.label());
        dto.setTone(tone(tier));
        dto.setScore(row.getScore());
        dto.setTriggerSource(row.getTriggerSource());
        dto.setTriggerLabel(triggerLabel(row.getTriggerSource()));
        dto.setAssessedAt(row.getAssessedAt());
        dto.setAssessedByStaffId(row.getAssessedByStaffId());
        if (engine != null) {
            GuidelineDto g = new GuidelineDto();
            g.setName(engine.guideline().name());
            g.setVersion(engine.guideline().version());
            g.setPublishedYear(engine.guideline().publishedYear());
            dto.setGuideline(g);
        }

        Map<String, Object> result = JsonUtils.fromJson(row.getResultJson(), new TypeReference<Map<String, Object>>() {});
        if (result != null) {
            dto.setAdvice(str(result.get("advice")));
            boolean legacy = !result.containsKey("factorGroups");
            dto.setLegacy(legacy);
            if (legacy) {
                fillLegacy(dto, result);
            } else {
                dto.setDimensions(maps(result.get("dimensions")));
                dto.setItems(maps(result.get("items")));
                dto.setFactorGroups(maps(result.get("factorGroups")));
                dto.setMissing(maps(result.get("missing")));
                dto.setComparisons(maps(result.get("comparisons")));
                if (result.get("notes") instanceof List<?> list) {
                    dto.setNotes(list.stream().map(String::valueOf).toList());
                }
                dto.setSuspicion(map(result.get("suspicion")));
            }
        }
        dto.setInputSnapshot(JsonUtils.fromJson(row.getInputSnapshotJson(), new TypeReference<Map<String, Object>>() {}));
        return dto;
    }

    private static void fillLegacy(AssessmentSnapshotDto dto, Map<String, Object> result) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> m : maps(result.get("items"))) {
            Map<String, Object> it = new LinkedHashMap<>(m);
            it.putIfAbsent("value", m.get("inputValue"));
            items.add(it);
        }
        dto.setItems(items);
        List<Map<String, Object>> missing = new ArrayList<>();
        if (result.get("missingFields") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("field", String.valueOf(o));
                m.put("label", String.valueOf(o));
                m.put("questionnaire", false);
                missing.add(m);
            }
        }
        dto.setMissing(missing);
    }

    public AssessmentRuleSheetDto toRuleSheet(AssessmentEngine engine) {
        AssessmentRuleSheetDto dto = new AssessmentRuleSheetDto();
        dto.setEngineCode(engine.code());
        dto.setEngineLabel(engine.label());
        dto.setCategory(engine.category().name());
        dto.setCategoryLabel(engine.category().label());
        dto.setDiseaseCode(engine.diseaseCode());
        dto.setDiseaseLabel(engine.disease().label());
        dto.setGuidelineName(engine.guideline().name());
        dto.setGuidelineVersion(engine.guideline().version());
        dto.setRulePackVersion(engine.rulePackVersion());
        dto.setProvisional(engine.provisional());
        dto.setLevels(engine.levels().stream()
                .map(l -> new AssessmentRuleSheetDto.LevelDto(
                        l.code(), l.label(),
                        l.tier() == null ? null : l.tier().name(),
                        l.tier() == null ? "无数据" : l.tier().label(),
                        tone(l.tier())))
                .toList());
        dto.setSheet(engine.ruleSheet());
        return dto;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
