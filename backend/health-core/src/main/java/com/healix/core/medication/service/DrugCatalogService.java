package com.healix.core.medication.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.service.DictService;
import com.healix.core.medication.dto.DrugCatalogItemDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DrugCatalogService {

    public static final String DRUG_CATALOG_PARENT = "drugCatalog";

    private final DictService dictService;

    public DrugCatalogService(DictService dictService) {
        this.dictService = dictService;
    }

    public List<DrugCatalogItemDto> search(String keyword, int limit) {
        int cap = limit <= 0 ? 20 : Math.min(limit, 50);
        String normalized = normalize(keyword);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<DrugCatalogItemDto> hits = new ArrayList<>();
        for (DictItemDto row : catalog()) {
            DrugCatalogItemDto item = toItem(row);
            if (matches(item, row.getContent(), normalized)) {
                hits.add(item);
                if (hits.size() >= cap) {
                    break;
                }
            }
        }
        return hits;
    }

    private List<DictItemDto> catalog() {
        return dictService.listMerged(
                DictService.PLATFORM_TENANT, DictTypeEnum.OPTION.name(), DRUG_CATALOG_PARENT);
    }

    private static DrugCatalogItemDto toItem(DictItemDto row) {
        DrugCatalogItemDto dto = new DrugCatalogItemDto();
        dto.setCode(row.getDictCode());
        dto.setDisplayName(row.getDictCodeDesc());
        JsonNode content = JsonUtils.readTree(row.getContent());
        dto.setGenericName(text(content, "genericName"));
        dto.setSpec(text(content, "spec"));
        dto.setDosageForm(text(content, "dosageForm"));
        dto.setCategory(text(content, "category"));
        dto.setDefaultDoseUnit(text(content, "defaultDoseUnit"));
        dto.setDefaultDoseAmount(text(content, "defaultDoseAmount"));
        dto.setDefaultUsageMethod(text(content, "defaultUsageMethod"));
        dto.setDefaultFrequency(text(content, "defaultFrequency"));
        if (!StringUtils.hasText(dto.getGenericName())) {
            dto.setGenericName(row.getDictCodeDesc());
        }
        return dto;
    }

    private static boolean matches(DrugCatalogItemDto item, String contentJson, String normalized) {
        if (contains(normalize(item.getDisplayName()), normalized)
                || contains(normalize(item.getGenericName()), normalized)
                || contains(normalize(item.getSpec()), normalized)) {
            return true;
        }
        JsonNode content = JsonUtils.readTree(contentJson);
        JsonNode aliases = content.get("aliases");
        if (aliases != null && aliases.isArray()) {
            for (JsonNode alias : aliases) {
                if (contains(normalize(alias.asText()), normalized)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String needle) {
        return StringUtils.hasText(haystack) && StringUtils.hasText(needle) && haystack.contains(needle);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return StringUtils.hasText(s) ? s : null;
    }

    private static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('μ', 'u')
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "");
    }
}
