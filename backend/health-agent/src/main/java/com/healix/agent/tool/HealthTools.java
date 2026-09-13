package com.healix.agent.tool;

import com.healix.core.identity.service.IdentityService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** C 端助手可调用的健康工具（当前仅过敏原查询被主动编排）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthTools {

    private final IdentityService identityService;

    @Tool(description = "List patient allergen names")
    public String queryAllergens(@ToolParam(description = "Patient ID") String patientId) {
        List<String> allergens = identityService.peopleAllergens(patientId);
        if (allergens.isEmpty()) {
            return "No known allergens";
        }
        return "Allergens: " + String.join(", ", allergens);
    }
}
