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
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CARE_PLAN.name(),
                "CARE_PLAN_GENERATE",
                "已为患者生成管理方案草稿，请审阅后发布。",
                List.of(
                        AgentAction.openSheet("查看方案草稿", "care-plan", cmd.peopleId()),
                        AgentAction.publishCarePlan("确认发布方案", cmd.peopleId()),
                        AgentAction.triggerCapability("继续闲聊", "GENERAL_CHAT", cmd.peopleId())),
                List.of(),
                bundle);
    }

    public static boolean matchesMessage(String message) {
        return matchesGenerateMessage(message) || matchesReviseMessage(message);
    }

    public static boolean matchesGenerateMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return containsAny(
                msg, "管理方案", "健康方案", "生成方案", "制定方案", "重新生成方案", "care plan", "care-plan");
    }

    /** 多轮修订意图：改/调草稿，而非整包重生成。 */
    public static boolean matchesReviseMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        if (wantsFullRegenerate(msg)) {
            return false;
        }
        return containsAny(
                msg,
                "改方案",
                "改一下方案",
                "调整方案",
                "修改方案",
                "修订方案",
                "改草稿",
                "调整草稿",
                "修改草稿",
                "调整运动",
                "调整饮食",
                "改运动",
                "改饮食",
                "改执行",
                "把运动",
                "把饮食",
                "把执行",
                "强度降低",
                "强度调低",
                "少跑步",
                "多步行",
                "revise plan",
                "revise care");
    }

    public static boolean wantsFullRegenerate(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return containsAny(msg, "重新生成", "从头生成", "重新制定", "再生成一版", "regenerate");
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
