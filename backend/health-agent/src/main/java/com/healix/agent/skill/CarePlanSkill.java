package com.healix.agent.skill;

import com.healix.agent.careplan.CarePlanAgentService;
import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class CarePlanSkill implements AgentSkill {

    private final CarePlanAgentService carePlanAgentService;

    @Override
    public AgentCapability capability() {
        return AgentCapability.CARE_PLAN;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.CARE_PLAN || matchesMessage(cmd.message());
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        AgentChatCommand cmd = ctx.command();
        String instruction = StringUtils.hasText(cmd.message()) ? cmd.message() : "生成管理方案";
        CarePlanBundleDto bundle = carePlanAgentService.generate(
                cmd.tenantId(),
                cmd.orgId(),
                cmd.peopleId(),
                cmd.staffId(),
                instruction,
                null,
                null,
                true);
        String path = "/workspace/patients/" + cmd.peopleId() + "/care-plan";
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CARE_PLAN.name(),
                "CARE_PLAN_GENERATE",
                "已为患者生成管理方案草稿，请审阅后发布。",
                List.of(AgentAction.navigate("查看方案草稿", path)),
                List.of(),
                bundle);
    }

    public static boolean matchesMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return containsAny(msg, "管理方案", "健康方案", "生成方案", "制定方案", "care plan", "care-plan");
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
