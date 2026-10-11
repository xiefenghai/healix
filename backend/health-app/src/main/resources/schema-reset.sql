-- Healix dev reset: 手动执行，清空全部业务表（会删除所有数据）
-- 用法: mysql -u healix -p healix < schema-reset.sql
-- 执行后再启动应用，会按 schema.sql 重建空表并由 Runner 灌种子数据

SET NAMES utf8mb4;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS sys_job_run;
DROP TABLE IF EXISTS sys_job_def;
DROP TABLE IF EXISTS feature_flag;
DROP TABLE IF EXISTS tenant_quota_usage;
DROP TABLE IF EXISTS intervention_record;
DROP TABLE IF EXISTS follow_up_form;
DROP TABLE IF EXISTS followup_record;
DROP TABLE IF EXISTS workspace_task;
DROP TABLE IF EXISTS follow_up_task;
DROP TABLE IF EXISTS followup_task;
DROP TABLE IF EXISTS alert_ticket;
DROP TABLE IF EXISTS health_report;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS agent_interaction_log;
DROP TABLE IF EXISTS agent_message;
DROP TABLE IF EXISTS agent_session;
DROP TABLE IF EXISTS vital_record;
DROP TABLE IF EXISTS care_team_member;
DROP TABLE IF EXISTS care_team;
DROP TABLE IF EXISTS field_revision_item;
DROP TABLE IF EXISTS field_revision_batch;
DROP TABLE IF EXISTS people_disease_archive;
DROP TABLE IF EXISTS people_metadata_info;
DROP TABLE IF EXISTS sys_dict;
DROP TABLE IF EXISTS people_basic_archive;
DROP TABLE IF EXISTS people_care_assignment;
DROP TABLE IF EXISTS people_org_membership;
DROP TABLE IF EXISTS people_activation_invite;
DROP TABLE IF EXISTS account_patient;
DROP TABLE IF EXISTS people_identity;
DROP TABLE IF EXISTS people_profile;
DROP TABLE IF EXISTS people_account;
DROP TABLE IF EXISTS patient_basic_archive;
DROP TABLE IF EXISTS patient_care_assignment;
DROP TABLE IF EXISTS patient_org_membership;
DROP TABLE IF EXISTS patient_profile;
DROP TABLE IF EXISTS patient_account;
DROP TABLE IF EXISTS staff_role_binding;
DROP TABLE IF EXISTS staff_org_binding;
DROP TABLE IF EXISTS staff_profile;
DROP TABLE IF EXISTS staff_account;
DROP TABLE IF EXISTS ops_account;
DROP TABLE IF EXISTS org_invite_code;
DROP TABLE IF EXISTS organization;
DROP TABLE IF EXISTS tenant;
SET FOREIGN_KEY_CHECKS = 1;
