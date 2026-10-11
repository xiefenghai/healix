package com.healix.core.allergy.dto;

import java.util.List;

public record AllergyCatalogDto(
        List<Option> categories,
        List<AllergenOption> allergens,
        List<ReactionOption> reactions,
        List<Option> severities,
        List<Option> types) {

    public record Option(String code, String label) {}

    public record AllergenOption(String code, String category, String label, List<String> aliases, String defaultType) {}

    public record ReactionOption(String code, String label, boolean highRisk) {}
}
