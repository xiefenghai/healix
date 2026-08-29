package com.healix.agent.log;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** Agent 交互日志（调优/审计；B端不可暴露 PATIENT 私聊原文） */
@Getter
@Setter
public class AgentInteractionLog extends BaseEntity {
    /** 会话业务ID */
    private String sessionId;
    /** Agent 类型：PATIENT/CARE_COPILOT */
    private String agentType;
    /** 租户业务ID */
    private String tenantId;
    /** 患者业务ID */
    private String peopleId;
    /** 员工业务ID */
    private String staffId;
    /** 识别意图 */
    private String intent;
    /** 用户原话 */
    private String userMessage;
    /** Prompt 快照 */
    private String promptSnapshot;
    /** 工具调用 JSON */
    private String toolCallsJson;
    /** 安全阀门前草稿回复 */
    private String draftReply;
    /** 最终回复 */
    private String finalReply;
    /** Prompt Token 数 */
    private Integer promptTokens;
    /** 补全 Token 数 */
    private Integer completionTokens;
}
