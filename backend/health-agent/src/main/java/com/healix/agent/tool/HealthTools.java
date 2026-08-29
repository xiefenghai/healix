package com.healix.agent.tool;

import com.healix.core.identity.service.IdentityService;
import com.healix.core.vitals.service.VitalService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthTools {

    private final VitalService vitalService;
    private final IdentityService identityService;

    @Tool(description = "Query today's step count for the given patient in a tenant")
    public String queryDailyStep(
            @ToolParam(description = "Tenant ID") String tenantId,
            @ToolParam(description = "Patient ID") String patientId) {
        Optional<BigDecimal> steps = vitalService.latestStepsToday(tenantId, patientId);
        return steps.map(s -> "Today steps: " + s).orElse("No step data recorded today");
    }

    @Tool(description = "Query average blood glucose over the last N days")
    public String queryGlucoseAverage(
            @ToolParam(description = "Tenant ID") String tenantId,
            @ToolParam(description = "Patient ID") String patientId,
            @ToolParam(description = "Number of days") int days) {
        return vitalService
                .averageGlucoseLastDays(tenantId, patientId, days)
                .map(avg -> String.format("Average glucose last %d days: %.2f mmol/L", days, avg))
                .orElse("No glucose data in the requested window");
    }

    @Tool(description = "List patient allergen names")
    public String queryAllergens(@ToolParam(description = "Patient ID") String patientId) {
        List<String> allergens = identityService.patientAllergens(patientId);
        if (allergens.isEmpty()) {
            return "No known allergens";
        }
        return "Allergens: " + String.join(", ", allergens);
    }
}
