package com.healix.core.assessment.support;

import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.dto.AssessmentTagView;
import com.healix.core.assessment.service.AssessmentOrchestrator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 组装患者评估标签（血糖分标 / 发病风险等），供驾驶舱与患者列表复用。 */
@Component
@RequiredArgsConstructor
public class AssessmentTagAssembler {

    private static final String CONTROL_ENGINE = "DIABETES_CONTROL_LABEL";
    private static final List<String[]> RISK_ENGINES = List.of(
            new String[] {"CDRS", "糖尿病"},
            new String[] {"HYPERTENSION_RISK", "高血压"},
            new String[] {"OBESITY_SCREEN", "肥胖"});

    private final AssessmentOrchestrator assessmentOrchestrator;

    public List<AssessmentTagView> assemble(String tenantId, String orgId, String peopleId) {
        AssessmentOverviewDto overview;
        try {
            overview = assessmentOrchestrator.overview(tenantId, orgId, peopleId);
        } catch (Exception ignored) {
            return List.of();
        }
        Map<String, AssessmentSnapshotDto> byCode = overview.getLatest().stream()
                .filter(s -> StringUtils.hasText(s.getEngineCode()))
                .collect(Collectors.toMap(
                        AssessmentSnapshotDto::getEngineCode, s -> s, (a, b) -> a, HashMap::new));
        Set<String> available = overview.getAvailableEngines().stream()
                .map(AssessmentOverviewDto.AvailableEngineDto::getEngineCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<AssessmentTagView> tags = new ArrayList<>();
        AssessmentSnapshotDto control = byCode.get(CONTROL_ENGINE);
        if (control != null || available.contains(CONTROL_ENGINE)) {
            // 缺项分标不当作评估结果展示
            if (control == null || !"INCOMPLETE".equals(control.getStatus())) {
                tags.add(controlTag(control));
            }
        }
        for (String[] meta : RISK_ENGINES) {
            String code = meta[0];
            String title = meta[1];
            AssessmentSnapshotDto snap = byCode.get(code);
            // 缺项：不进入评估标签
            if (snap != null && "INCOMPLETE".equals(snap.getStatus())) {
                continue;
            }
            String label = null;
            if (snap != null && StringUtils.hasText(snap.getLevelLabel())) {
                label = snap.getLevelLabel();
            } else if (snap != null && StringUtils.hasText(snap.getLevel())) {
                label = snap.getLevel();
            } else if (!available.contains(code)) {
                continue;
            }
            boolean hasResult = snap != null && StringUtils.hasText(snap.getLevel());
            String text = displayRiskText(title, label, hasResult);
            AssessmentTagView tag = new AssessmentTagView();
            tag.setEngineCode(code);
            tag.setText(text);
            tag.setTone(riskTone(snap));
            tag.setTitle(title);
            tags.add(tag);
        }
        return tags;
    }

    /** 保证一眼能看出评估类型，避免单独出现「正常」。 */
    private static String displayRiskText(String title, String label, boolean hasResult) {
        if (!hasResult || !StringUtils.hasText(label)) {
            return title + "未评估";
        }
        if (label.contains(title)) {
            return label;
        }
        if ("高血压".equals(title) && label.contains("血压")) {
            return label;
        }
        if ("肥胖".equals(title)
                && (label.contains("肥胖") || label.contains("超重") || label.toUpperCase().contains("BMI"))) {
            return label;
        }
        return title + label;
    }

    private static AssessmentTagView controlTag(AssessmentSnapshotDto snap) {
        AssessmentTagView tag = new AssessmentTagView();
        tag.setEngineCode(CONTROL_ENGINE);
        tag.setTitle("血糖控制分标");
        String level = snap == null ? null : snap.getLevel();
        String tone = "muted";
        if ("RED".equals(level)) {
            tone = "danger";
        } else if ("YELLOW".equals(level)) {
            tone = "warning";
        } else if ("GREEN".equals(level) || "NEAR_GREEN".equals(level)) {
            tone = "success";
        } else if ("NONE".equals(level)) {
            tone = "info";
        }
        String label;
        if (snap == null) {
            label = "未评估";
        } else if ("NONE".equals(level)) {
            label = "未分标";
        } else if (StringUtils.hasText(snap.getLevelLabel())) {
            label = snap.getLevelLabel();
        } else if (StringUtils.hasText(level)) {
            label = level;
        } else {
            label = "未评估";
        }
        tag.setText("血糖" + label);
        tag.setTone(tone);
        return tag;
    }

    private static String riskTone(AssessmentSnapshotDto snap) {
        if (snap == null || "INCOMPLETE".equals(snap.getStatus()) || !StringUtils.hasText(snap.getLevel())) {
            return "muted";
        }
        String level = snap.getLevel();
        if ("HIGH".equals(level)
                || "GRADE_3".equals(level)
                || "GRADE_2".equals(level)
                || level.contains("SEVERE")
                || "EXTREME_OBESITY".equals(level)) {
            return "danger";
        }
        if ("MID".equals(level)
                || "GRADE_1".equals(level)
                || "PREHYPERTENSION".equals(level)
                || "OVERWEIGHT".equals(level)
                || "MILD_OBESITY".equals(level)
                || "MODERATE_OBESITY".equals(level)) {
            return "warning";
        }
        return "success";
    }
}
