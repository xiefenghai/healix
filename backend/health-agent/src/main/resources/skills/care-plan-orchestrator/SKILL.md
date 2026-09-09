# Care Plan Orchestrator

主智能体：加载 `management-plan` Skill，为患者生成健康管理方案草稿。

## 编排

1. `CarePlanContextTools.loadCarePlanContext`
2. LLM 结构化生成（`healix.agent.enabled=true`）或模板降级
3. Safety → `care_plan_draft`，不自动发布

## 约束

- 仅 CARE_COPILOT
- 整包生成运动 + 饮食 + 执行 + 总结
