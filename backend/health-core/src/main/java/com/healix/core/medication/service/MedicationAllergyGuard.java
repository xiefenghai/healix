package com.healix.core.medication.service;

import com.healix.core.allergy.domain.PeopleAllergy;
import com.healix.core.allergy.enums.AllergyCategoryEnum;
import com.healix.core.allergy.enums.AllergyTypeEnum;
import com.healix.core.allergy.service.AllergyQueryService;
import com.healix.core.allergy.support.AllergenCatalog;
import com.healix.core.medication.dto.DrugCatalogItemDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用药 × 药物过敏比对：档案中生效的药物类过敏，按字典类别展开别名（青霉素类 → 阿莫西林…），
 * 与药名 / 目录通用名做包含匹配。只提示不拦截。
 */
@Service
@RequiredArgsConstructor
public class MedicationAllergyGuard {

    private final AllergyQueryService allergyQueryService;
    private final DrugCatalogService drugCatalogService;

    /** @return 冲突描述，如「青霉素类（阿莫西林）」；无冲突为空列表 */
    public List<String> check(String peopleId, String drugCode, String drugName) {
        List<String> names = new ArrayList<>();
        if (StringUtils.hasText(drugName)) {
            names.add(normalize(drugName));
        }
        DrugCatalogItemDto catalog = drugCatalogService.findByCode(drugCode);
        if (catalog != null) {
            if (StringUtils.hasText(catalog.getGenericName())) {
                names.add(normalize(catalog.getGenericName()));
            }
            if (StringUtils.hasText(catalog.getDisplayName())) {
                names.add(normalize(catalog.getDisplayName()));
            }
        }
        if (names.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (PeopleAllergy allergy : allergyQueryService.effective(peopleId)) {
            if (!isDrugAllergy(allergy)) {
                continue;
            }
            String hit = null;
            for (String keyword : AllergenCatalog.keywordsOf(allergy.getAllergenCode(), allergy.getAllergenName())) {
                String k = normalize(keyword);
                if (k.length() < 2) {
                    continue;
                }
                if (names.stream().anyMatch(n -> n.contains(k))) {
                    hit = keyword;
                    break;
                }
            }
            if (hit != null) {
                String label = allergy.getAllergenName();
                out.add(label.equals(hit) ? label : label + "（" + hit + "）");
            }
        }
        return out;
    }

    private static boolean isDrugAllergy(PeopleAllergy row) {
        if (AllergyTypeEnum.INTOLERANCE.name().equals(row.getAllergyType())) {
            return false;
        }
        return AllergyCategoryEnum.DRUG.name().equals(row.getCategory())
                || AllergyCategoryEnum.OTHER.name().equals(row.getCategory());
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
