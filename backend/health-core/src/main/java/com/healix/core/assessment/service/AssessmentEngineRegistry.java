package com.healix.core.assessment.service;

import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.engine.AssessmentEngine;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 引擎注册表：按编码 / 病种 / 类别查找。 */
@Component
public class AssessmentEngineRegistry {

    private final List<AssessmentEngine> engines;
    private final Map<String, AssessmentEngine> byCode = new LinkedHashMap<>();

    public AssessmentEngineRegistry(List<AssessmentEngine> engines) {
        this.engines = engines.stream()
                .sorted((a, b) -> {
                    int d = a.disease().compareTo(b.disease());
                    return d != 0 ? d : a.category().compareTo(b.category());
                })
                .toList();
        this.engines.forEach(e -> byCode.put(e.code(), e));
    }

    public List<AssessmentEngine> all() {
        return engines;
    }

    public AssessmentEngine get(String code) {
        return code == null ? null : byCode.get(code.trim());
    }

    public AssessmentEngine find(AssessmentDisease disease, AssessmentCategory category) {
        return engines.stream()
                .filter(e -> e.disease() == disease && e.category() == category)
                .findFirst()
                .orElse(null);
    }

    /** 若干引擎的输入字段并集。 */
    public static Set<InputField> inputsOf(List<AssessmentEngine> list) {
        Set<InputField> out = EnumSet.noneOf(InputField.class);
        list.forEach(e -> out.addAll(e.inputs()));
        return out;
    }
}
