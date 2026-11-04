package com.healix.core.assessment.support;

import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.domain.PeopleAssessmentLatest;
import com.healix.core.assessment.dto.AssessmentTagView;
import com.healix.core.assessment.engine.AssessmentEngine;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.service.AssessmentEngineRegistry;
import com.healix.core.assessment.service.AssessmentViewMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 患者评估标签（患者列表、驾驶舱、Agent 共用）：只读最新结果表，按页批量一次查询。
 *
 * <p>每个病种最多一个标签（当前生效引擎），颜色按统一等级；缺项结果不出标签。
 */
@Component
@RequiredArgsConstructor
public class AssessmentTagAssembler {

    private static final int BATCH = 500;

    private final AssessmentStoreMapper storeMapper;
    private final AssessmentEngineRegistry registry;

    public List<AssessmentTagView> assemble(String tenantId, String orgId, String peopleId) {
        return assembleBatch(tenantId, List.of(peopleId)).getOrDefault(peopleId, List.of());
    }

    public Map<String, List<AssessmentTagView>> assembleBatch(String tenantId, Collection<String> peopleIds) {
        Map<String, List<AssessmentTagView>> out = new HashMap<>();
        if (!StringUtils.hasText(tenantId) || peopleIds == null || peopleIds.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(peopleIds));
        ids.removeIf(id -> !StringUtils.hasText(id));
        for (int i = 0; i < ids.size(); i += BATCH) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + BATCH));
            for (PeopleAssessmentLatest row : storeMapper.listLatestByPeopleIds(tenantId, chunk)) {
                AssessmentTagView tag = toTag(row);
                if (tag != null) {
                    out.computeIfAbsent(row.getPeopleId(), k -> new ArrayList<>()).add(tag);
                }
            }
        }
        Comparator<AssessmentTagView> order = Comparator.comparingInt(t -> diseaseOrder(t.getDiseaseCode()));
        out.values().forEach(list -> list.sort(order));
        return out;
    }

    private AssessmentTagView toTag(PeopleAssessmentLatest row) {
        if (!row.effective() || !AssessmentStatus.COMPLETE.name().equals(row.getStatus())) {
            return null;
        }
        AssessmentEngine engine = registry.get(row.getEngineCode());
        if (engine == null) {
            return null;
        }
        AssessmentTier tier = AssessmentTier.parse(row.getTier());
        String levelLabel = StringUtils.hasText(row.getLevelLabel()) ? row.getLevelLabel() : row.getLevel();
        AssessmentTagView tag = new AssessmentTagView();
        tag.setEngineCode(engine.code());
        tag.setDiseaseCode(engine.diseaseCode());
        tag.setCategory(engine.category().name());
        tag.setLevel(row.getLevel());
        tag.setTier(tier == null ? null : tier.name());
        tag.setText(tagText(engine, levelLabel));
        tag.setTone(AssessmentViewMapper.tone(tier));
        tag.setTitle(engine.label() + "：" + levelLabel);
        tag.setAssessedAt(row.getAssessedAt());
        return tag;
    }

    /** 保证一眼看出评估类型，避免单独出现「正常」「低」。 */
    static String tagText(AssessmentEngine engine, String levelLabel) {
        String disease = engine.disease().label();
        if (engine.category() == AssessmentCategory.RISK) {
            return switch (engine.disease()) {
                case OBESITY -> {
                    if (levelLabel.startsWith("肥胖")) {
                        yield "肥胖";
                    }
                    yield levelLabel.contains("肥胖") || "超重".equals(levelLabel) ? levelLabel : "体重" + levelLabel;
                }
                default -> disease + levelLabel;
            };
        }
        return switch (engine.disease()) {
            case DIABETES -> "血糖" + levelLabel;
            case HYPERTENSION -> "高血压" + levelLabel;
            case OBESITY -> "肥胖分层" + levelLabel;
        };
    }

    private static int diseaseOrder(String code) {
        if (code == null) {
            return 9;
        }
        return switch (code) {
            case "diabetes" -> 0;
            case "hypertension" -> 1;
            case "obesity" -> 2;
            default -> 9;
        };
    }

    /** 驾驶舱 / 列表优先级排序用：最高统一等级。 */
    public static AssessmentTier maxTier(List<AssessmentTagView> tags) {
        AssessmentTier max = null;
        if (tags != null) {
            for (AssessmentTagView t : tags) {
                max = AssessmentTier.max(max, AssessmentTier.parse(t.getTier()));
            }
        }
        return max;
    }
}
