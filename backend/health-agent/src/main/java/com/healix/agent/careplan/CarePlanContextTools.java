package com.healix.agent.careplan;

import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanLoadTracer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 健康管理方案上下文 Tools（供 CARE_COPILOT Skills / 编排使用）。 */
@Component
@RequiredArgsConstructor
public class CarePlanContextTools {

    private final CarePlanContextService contextService;

    @Tool(description = "Load care-plan generation context: archive, diseases, allergens, meds, metrics, labs")
    public CarePlanContext loadCarePlanContext(
            @ToolParam(description = "Tenant ID") String tenantId,
            @ToolParam(description = "Patient people ID") String peopleId) {
        return contextService.load(tenantId, peopleId);
    }

    public CarePlanContext loadCarePlanContext(
            String tenantId, String peopleId, CarePlanLoadTracer tracer) {
        return contextService.load(tenantId, peopleId, tracer);
    }
}
