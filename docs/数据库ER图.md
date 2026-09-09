# Healix 数据库 ER 图

> **请打开可视化页面（推荐）**：[数据库ER图.html](./数据库ER图.html)  
> 打开后默认是「整体总览」关系图；再点左侧域看字段。支持搜索、点击节点跳转。

来源：`backend/health-app/src/main/resources/schema.sql`（v0.5）

## 约定

| 项 | 说明 |
|---|---|
| `pk_id` | 物理自增主键 |
| `id` | 业务主键（雪花 `VARCHAR(32)`） |
| 表间关联 | 一律用业务 `id`，**无物理 FOREIGN KEY** |
| 全表公共字段 | `is_deleted` / `gmt_created` / `gmt_modified` / `gmt_deleted` |

共 **44** 张表（已删空壳 `alert_ticket`/`follow_up_task`/`intervention_record`，新增 `followup_record`）。

## 域一览

| 域 | 表 |
|---|---|
| 平台与机构 | `tenant` `organization` `org_invite_code` `ops_account` `sys_dict` `tenant_quota_usage` `feature_flag` |
| B 端人员 | `staff_account` `staff_profile` `staff_org_binding` `staff_role_binding` |
| 患者与入组 | `people_account` `people_profile` `people_identity` `account_patient` `people_activation_invite` `people_org_membership` `people_care_assignment` `people_basic_archive` `people_disease_archive` `people_metadata_info` |
| 健管组 | `care_team` `care_team_member` |
| 健康数据 | `vital_record` `lab_report` `lab_result_item` `exam_report` `people_medication` `people_medication_intake` |
| 管理方案 | `care_plan` `care_plan_draft` `care_plan_version` `care_plan_task` `care_plan_task_checkin` |
| 工作台与随访 | `workspace_task` `followup_record` |
| 修订与审计 | `field_revision_batch` `field_revision_item` `audit_log` |
| Agent | `agent_session` `agent_message` `agent_interaction_log` |
| P2 预留 | `health_report` `tenant_quota_usage` `feature_flag` |

## 核心关系（文字）

```
tenant
  └─ organization
       ├─ org_invite_code
       ├─ staff_org_binding ← staff_profile ← staff_account
       ├─ care_team → care_team_member
       └─ people_org_membership → people_profile
            ├─ people_identity / account_patient / activation_invite
            ├─ people_basic_archive / people_disease_archive
            ├─ vital_record / lab_report→lab_result_item / exam_report
            ├─ people_medication → people_medication_intake
            └─ care_plan
                 ├─ care_plan_draft
                 ├─ care_plan_version
                 └─ care_plan_task → care_plan_task_checkin
            └─ workspace_task ←→ followup_record
```

完整字段与可点击关系见 **HTML 页面**。
