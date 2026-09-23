package com.healix.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 幂等补齐存量库表结构（开发期轻量迁移）。 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaEnsureRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureTable(
                "staff_patient_watch",
                """
                CREATE TABLE IF NOT EXISTS staff_patient_watch (
                    pk_id           BIGINT       NOT NULL AUTO_INCREMENT,
                    id              VARCHAR(32)  NOT NULL,
                    tenant_id       VARCHAR(32)  NOT NULL,
                    org_id          VARCHAR(32)  NOT NULL,
                    staff_id        VARCHAR(32)  NOT NULL,
                    people_id       VARCHAR(32)  NOT NULL,
                    is_deleted      TINYINT      NOT NULL DEFAULT 0,
                    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_staff_patient_watch_id (id),
                    UNIQUE KEY uk_staff_patient_watch (staff_id, org_id, people_id, gmt_deleted),
                    KEY idx_staff_patient_watch_org_staff (org_id, staff_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        ensureColumn(
                "people_medication",
                "timing_note",
                "ALTER TABLE people_medication ADD COLUMN timing_note VARCHAR(128) NULL COMMENT '用药时机：晨起/饭后/痛时服等' AFTER stop_date");
        ensureColumn(
                "people_medication",
                "course_days",
                "ALTER TABLE people_medication ADD COLUMN course_days INT NULL COMMENT '疗程天数；空且无停药日可表示长期' AFTER timing_note");

        ensureColumn(
                "vital_record",
                "org_id",
                "ALTER TABLE vital_record ADD COLUMN org_id VARCHAR(32) NULL COMMENT '录入时工作机构业务ID' AFTER people_id");
        ensureColumn(
                "vital_record",
                "group_id",
                "ALTER TABLE vital_record ADD COLUMN group_id VARCHAR(32) NULL COMMENT '同一次测量分组ID' AFTER source");
        ensureColumn(
                "vital_record",
                "note",
                "ALTER TABLE vital_record ADD COLUMN note VARCHAR(200) NULL COMMENT '备注' AFTER group_id");
        ensureColumn(
                "vital_record",
                "recorded_by_staff_id",
                "ALTER TABLE vital_record ADD COLUMN recorded_by_staff_id VARCHAR(32) NULL COMMENT 'B端代录人员工业务ID' AFTER note");

        ensureTable(
                "lab_report",
                """
                CREATE TABLE IF NOT EXISTS lab_report (
                    pk_id               BIGINT       NOT NULL AUTO_INCREMENT,
                    id                  VARCHAR(32)  NOT NULL,
                    tenant_id           VARCHAR(32)  NOT NULL,
                    people_id           VARCHAR(32)  NOT NULL,
                    org_id              VARCHAR(32)  NULL,
                    specimen_type       VARCHAR(32)  NOT NULL DEFAULT 'BLOOD',
                    sampled_at          DATETIME     NULL,
                    reported_at         DATETIME     NULL,
                    source              VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
                    note                VARCHAR(200) NULL,
                    created_by_staff_id VARCHAR(32)  NULL,
                    updated_by_staff_id VARCHAR(32)  NULL,
                    is_deleted          TINYINT      NOT NULL DEFAULT 0,
                    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_lab_report_id (id),
                    KEY idx_lab_report_people (tenant_id, people_id, reported_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "lab_result_item",
                """
                CREATE TABLE IF NOT EXISTS lab_result_item (
                    pk_id           BIGINT         NOT NULL AUTO_INCREMENT,
                    id              VARCHAR(32)    NOT NULL,
                    report_id       VARCHAR(32)    NOT NULL,
                    item_code       VARCHAR(32)    NOT NULL,
                    item_name       VARCHAR(64)    NOT NULL,
                    value_num       DECIMAL(16, 4) NULL,
                    value_text      VARCHAR(64)    NULL,
                    unit            VARCHAR(32)    NULL,
                    ref_low         DECIMAL(16, 4) NULL,
                    ref_high        DECIMAL(16, 4) NULL,
                    abnormal_flag   VARCHAR(8)     NULL,
                    is_deleted      TINYINT        NOT NULL DEFAULT 0,
                    gmt_created     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted     DATETIME       NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_lab_result_item_id (id),
                    KEY idx_lab_item_report (report_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "exam_report",
                """
                CREATE TABLE IF NOT EXISTS exam_report (
                    pk_id               BIGINT       NOT NULL AUTO_INCREMENT,
                    id                  VARCHAR(32)  NOT NULL,
                    tenant_id           VARCHAR(32)  NOT NULL,
                    people_id           VARCHAR(32)  NOT NULL,
                    org_id              VARCHAR(32)  NULL,
                    exam_type           VARCHAR(32)  NOT NULL,
                    examined_at         DATETIME     NOT NULL,
                    conclusion          VARCHAR(1000) NULL,
                    findings_json       JSON         NULL,
                    source              VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
                    created_by_staff_id VARCHAR(32)  NULL,
                    updated_by_staff_id VARCHAR(32)  NULL,
                    is_deleted          TINYINT      NOT NULL DEFAULT 0,
                    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_exam_report_id (id),
                    KEY idx_exam_report_people (tenant_id, people_id, examined_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureColumn(
                "exam_report",
                "source",
                "ALTER TABLE exam_report ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：STAFF/PATIENT' AFTER findings_json");

        ensureColumn(
                "care_plan_draft",
                "schema_version",
                "ALTER TABLE care_plan_draft ADD COLUMN schema_version INT NOT NULL DEFAULT 1 COMMENT '产品规则版本' AFTER version");
        ensureColumn(
                "care_plan_version",
                "schema_version",
                "ALTER TABLE care_plan_version ADD COLUMN schema_version INT NOT NULL DEFAULT 1 COMMENT '产品规则版本' AFTER version_no");
        ensureColumn(
                "care_plan_version",
                "title",
                "ALTER TABLE care_plan_version ADD COLUMN title VARCHAR(128) NULL COMMENT '发布时标题快照' AFTER source");

        ensureTable(
                "care_plan",
                """
                CREATE TABLE IF NOT EXISTS care_plan (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
                    title                   VARCHAR(128) NULL,
                    goal_summary            VARCHAR(512) NULL,
                    disease_tags_json       JSON         NULL,
                    current_version_id      VARCHAR(32)  NULL,
                    version                 INT          NOT NULL DEFAULT 1,
                    created_by_staff_id     VARCHAR(32)  NULL,
                    updated_by_staff_id     VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_plan_id (id),
                    UNIQUE KEY uk_care_plan_tenant_people (tenant_id, people_id, gmt_deleted),
                    KEY idx_care_plan_people (people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "care_plan_draft",
                """
                CREATE TABLE IF NOT EXISTS care_plan_draft (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    plan_id                 VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    version                 INT          NOT NULL DEFAULT 1,
                    schema_version          INT          NOT NULL DEFAULT 1,
                    source                  VARCHAR(32)  NOT NULL DEFAULT 'MANUAL',
                    exercise_json           JSON         NULL,
                    diet_json               JSON         NULL,
                    execution_json          JSON         NULL,
                    safety_flags_json       JSON         NULL,
                    context_snapshot_json   JSON         NULL,
                    base_version_id         VARCHAR(32)  NULL,
                    generation_log_id       VARCHAR(32)  NULL,
                    updated_by_staff_id     VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_plan_draft_id (id),
                    UNIQUE KEY uk_care_plan_draft_plan (plan_id, gmt_deleted),
                    KEY idx_care_plan_draft_people (tenant_id, people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "care_plan_version",
                """
                CREATE TABLE IF NOT EXISTS care_plan_version (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    plan_id                 VARCHAR(32)  NOT NULL,
                    version_no              INT          NOT NULL,
                    schema_version          INT          NOT NULL DEFAULT 1,
                    source                  VARCHAR(32)  NOT NULL,
                    title                   VARCHAR(128) NULL,
                    exercise_json           JSON         NULL,
                    diet_json               JSON         NULL,
                    execution_json          JSON         NULL,
                    context_snapshot_json   JSON         NULL,
                    safety_flags_json       JSON         NULL,
                    published_at            DATETIME     NOT NULL,
                    published_by_staff_id   VARCHAR(32)  NOT NULL,
                    signed_by_staff_id      VARCHAR(32)  NULL,
                    signed_at               DATETIME     NULL,
                    sign_status             VARCHAR(16)  NOT NULL DEFAULT 'UNSIGNED',
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_plan_version_id (id),
                    UNIQUE KEY uk_care_plan_version_no (plan_id, version_no, gmt_deleted),
                    KEY idx_care_plan_version_plan (plan_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "care_plan_task",
                """
                CREATE TABLE IF NOT EXISTS care_plan_task (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    plan_id                 VARCHAR(32)  NOT NULL,
                    plan_version_id         VARCHAR(32)  NOT NULL,
                    task_code               VARCHAR(64)  NOT NULL,
                    title                   VARCHAR(128) NOT NULL,
                    category                VARCHAR(32)  NOT NULL,
                    frequency               VARCHAR(32)  NULL,
                    time_slot               VARCHAR(32)  NULL,
                    related_ref             VARCHAR(64)  NULL,
                    enabled                 TINYINT      NOT NULL DEFAULT 1,
                    sort_order              INT          NOT NULL DEFAULT 0,
                    payload_json            JSON         NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_plan_task_id (id),
                    KEY idx_care_plan_task_version (plan_version_id),
                    KEY idx_care_plan_task_people (tenant_id, people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "care_plan_task_checkin",
                """
                CREATE TABLE IF NOT EXISTS care_plan_task_checkin (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    plan_id                 VARCHAR(32)  NOT NULL,
                    plan_version_id         VARCHAR(32)  NOT NULL,
                    task_id                 VARCHAR(32)  NOT NULL,
                    checkin_date            DATE         NOT NULL,
                    time_slot               VARCHAR(32)  NOT NULL DEFAULT 'ALL',
                    status                  VARCHAR(16)  NOT NULL,
                    note                    VARCHAR(200) NULL,
                    recorded_by_people_id   VARCHAR(32)  NULL,
                    recorded_by_staff_id    VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_plan_task_checkin_id (id),
                    UNIQUE KEY uk_care_plan_task_checkin_slot (task_id, checkin_date, time_slot, gmt_deleted),
                    KEY idx_care_plan_task_checkin_people_date (tenant_id, people_id, checkin_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        ensureTable(
                "account_patient",
                """
                CREATE TABLE IF NOT EXISTS account_patient (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    account_id              VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    display_name            VARCHAR(64)  NOT NULL,
                    relation                VARCHAR(32)  NULL,
                    identity_type           VARCHAR(32)  NULL,
                    identity_value_hash     VARCHAR(64)  NULL,
                    identity_value_mask     VARCHAR(64)  NULL,
                    mobile                  VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_account_patient_id (id),
                    UNIQUE KEY uk_account_patient_identity (account_id, identity_type, identity_value_hash, gmt_deleted),
                    KEY idx_account_patient_account (account_id),
                    KEY idx_account_patient_people (people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "people_activation_invite",
                """
                CREATE TABLE IF NOT EXISTS people_activation_invite (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    org_id                  VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    code                    VARCHAR(32)  NOT NULL,
                    enabled                 TINYINT      NOT NULL DEFAULT 1,
                    expire_at               DATETIME     NULL,
                    used_at                 DATETIME     NULL,
                    used_by_account_id      VARCHAR(32)  NULL,
                    created_by_staff_id     VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_people_activation_invite_id (id),
                    UNIQUE KEY uk_people_activation_invite_code (code, gmt_deleted),
                    KEY idx_people_activation_invite_people (people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "sys_job_def",
                """
                CREATE TABLE IF NOT EXISTS sys_job_def (
                    pk_id           BIGINT       NOT NULL AUTO_INCREMENT,
                    id              VARCHAR(32)  NOT NULL,
                    job_code        VARCHAR(64)  NOT NULL,
                    display_name    VARCHAR(128) NOT NULL,
                    cron_expr       VARCHAR(64)  NOT NULL,
                    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
                    enabled         TINYINT      NOT NULL DEFAULT 1,
                    params_json     TEXT         NULL,
                    next_fire_at    DATETIME     NULL,
                    last_fire_at    DATETIME     NULL,
                    last_status     VARCHAR(32)  NULL,
                    last_message    VARCHAR(512) NULL,
                    is_deleted      TINYINT      NOT NULL DEFAULT 0,
                    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_job_def_id (id),
                    UNIQUE KEY uk_job_def_code (job_code, gmt_deleted),
                    KEY idx_job_def_due (enabled, next_fire_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "sys_job_run",
                """
                CREATE TABLE IF NOT EXISTS sys_job_run (
                    pk_id           BIGINT       NOT NULL AUTO_INCREMENT,
                    id              VARCHAR(32)  NOT NULL,
                    job_def_id      VARCHAR(32)  NOT NULL,
                    job_code        VARCHAR(64)  NOT NULL,
                    trigger_type    VARCHAR(16)  NOT NULL,
                    status          VARCHAR(16)  NOT NULL,
                    started_at      DATETIME     NOT NULL,
                    finished_at     DATETIME     NULL,
                    message         VARCHAR(512) NULL,
                    detail_json     TEXT         NULL,
                    instance_id     VARCHAR(128) NULL,
                    triggered_by    VARCHAR(32)  NULL,
                    is_deleted      TINYINT      NOT NULL DEFAULT 0,
                    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_job_run_id (id),
                    KEY idx_job_run_code_time (job_code, started_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        ensureTable(
                "adherence_daily_snapshot",
                """
                CREATE TABLE IF NOT EXISTS adherence_daily_snapshot (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    org_id                  VARCHAR(32)  NOT NULL,
                    care_team_id            VARCHAR(32)  NOT NULL DEFAULT '',
                    snapshot_date           DATE         NOT NULL,
                    universe_count          INT          NOT NULL DEFAULT 0,
                    follow_up_count         INT          NOT NULL DEFAULT 0,
                    plan_incomplete_count   INT          NOT NULL DEFAULT 0,
                    med_incomplete_count    INT          NOT NULL DEFAULT 0,
                    streak_ge3_count        INT          NOT NULL DEFAULT 0,
                    plan_due_sum            INT          NOT NULL DEFAULT 0,
                    plan_done_sum           INT          NOT NULL DEFAULT 0,
                    med_due_dose_sum        INT          NOT NULL DEFAULT 0,
                    med_taken_dose_sum      INT          NOT NULL DEFAULT 0,
                    plan_rate               DECIMAL(6,4) NULL,
                    med_rate                DECIMAL(6,4) NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_adherence_snapshot_id (id),
                    UNIQUE KEY uk_adherence_snapshot_scope (org_id, care_team_id, snapshot_date),
                    KEY idx_adherence_snapshot_tenant (tenant_id, snapshot_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        ensureColumn(
                "people_profile",
                "merged_into_people_id",
                "ALTER TABLE people_profile ADD COLUMN merged_into_people_id VARCHAR(32) NULL COMMENT '已合并到的目标 people_id' AFTER emergency_contact_json");

        ensureColumn(
                "people_profile",
                "mobile",
                "ALTER TABLE people_profile ADD COLUMN mobile VARCHAR(32) NULL COMMENT '联系手机号' AFTER name_pinyin");
        ensureColumn(
                "people_profile",
                "address",
                "ALTER TABLE people_profile ADD COLUMN address VARCHAR(256) NULL COMMENT '家庭住址' AFTER mobile");
        ensureColumn(
                "people_profile",
                "education_level",
                "ALTER TABLE people_profile ADD COLUMN education_level VARCHAR(32) NULL COMMENT '文化程度' AFTER address");
        ensureColumn(
                "people_profile",
                "marital_status",
                "ALTER TABLE people_profile ADD COLUMN marital_status VARCHAR(32) NULL COMMENT '婚姻状况' AFTER education_level");
        ensureColumn(
                "people_profile",
                "occupation",
                "ALTER TABLE people_profile ADD COLUMN occupation VARCHAR(64) NULL COMMENT '职业' AFTER marital_status");

        ensureTable(
                "account_mfa",
                """
                CREATE TABLE IF NOT EXISTS account_mfa (
                    pk_id           BIGINT       NOT NULL AUTO_INCREMENT,
                    id              VARCHAR(32)  NOT NULL,
                    account_type    VARCHAR(16)  NOT NULL,
                    account_id      VARCHAR(32)  NOT NULL,
                    secret          VARCHAR(64)  NOT NULL,
                    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                    enabled_at      DATETIME     NULL,
                    is_deleted      TINYINT      NOT NULL DEFAULT 0,
                    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_account_mfa_id (id),
                    UNIQUE KEY uk_account_mfa (account_type, account_id, gmt_deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        ensureTable(
                "tenant_config",
                """
                CREATE TABLE IF NOT EXISTS tenant_config (
                    pk_id           BIGINT       NOT NULL AUTO_INCREMENT,
                    id              VARCHAR(32)  NOT NULL,
                    tenant_id       VARCHAR(32)  NOT NULL,
                    app_name        VARCHAR(64)  NULL,
                    logo_url        VARCHAR(512) NULL,
                    primary_color   VARCHAR(16)  NULL,
                    login_slogan    VARCHAR(128) NULL,
                    support_phone   VARCHAR(32)  NULL,
                    is_deleted      TINYINT      NOT NULL DEFAULT 0,
                    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_tenant_config_id (id),
                    UNIQUE KEY uk_tenant_config_tenant (tenant_id, gmt_deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // 平台级开关用空串占位；存量库若为 NULL，唯一键不会去重
        tryExecute("UPDATE feature_flag SET tenant_id = '' WHERE tenant_id IS NULL");

        ensureTable(
                "workspace_task",
                """
                CREATE TABLE IF NOT EXISTS workspace_task (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    org_id                  VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    task_type               VARCHAR(32)  NOT NULL,
                    biz_key                 VARCHAR(128) NOT NULL,
                    open_dedup_key          VARCHAR(160) GENERATED ALWAYS AS (
                        CASE
                            WHEN is_deleted = 0 AND status = 'OPEN' THEN CONCAT(task_type, ':', biz_key)
                            ELSE id
                        END
                    ) STORED,
                    status                  VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
                    priority                VARCHAR(16)  NOT NULL DEFAULT 'HIGH',
                    assignee_staff_id       VARCHAR(32)  NULL,
                    title                   VARCHAR(128) NOT NULL,
                    summary                 VARCHAR(512) NULL,
                    payload_json            JSON         NULL,
                    source                  VARCHAR(16)  NOT NULL DEFAULT 'JOB',
                    close_reason            VARCHAR(16)  NULL,
                    due_at                  DATETIME     NULL,
                    opened_at               DATETIME     NOT NULL,
                    done_at                 DATETIME     NULL,
                    done_by_staff_id        VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_workspace_task_id (id),
                    UNIQUE KEY uk_workspace_task_open (tenant_id, org_id, open_dedup_key, gmt_deleted),
                    KEY idx_workspace_task_org_assignee (org_id, status, assignee_staff_id),
                    KEY idx_workspace_task_org_people (org_id, people_id, status),
                    KEY idx_workspace_task_org_type (org_id, task_type, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureTable(
                "followup_record",
                """
                CREATE TABLE IF NOT EXISTS followup_record (
                    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
                    id                      VARCHAR(32)  NOT NULL,
                    tenant_id               VARCHAR(32)  NOT NULL,
                    org_id                  VARCHAR(32)  NOT NULL,
                    people_id               VARCHAR(32)  NOT NULL,
                    workspace_task_id       VARCHAR(32)  NULL,
                    record_type             VARCHAR(32)  NOT NULL,
                    source                  VARCHAR(32)  NOT NULL,
                    status                  VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
                    title                   VARCHAR(128) NOT NULL,
                    summary                 VARCHAR(512) NULL,
                    planned_at              DATETIME     NULL,
                    due_at                  DATETIME     NULL,
                    assignee_staff_id       VARCHAR(32)  NULL,
                    contact_channel         VARCHAR(32)  NULL,
                    contact_result          VARCHAR(32)  NULL,
                    content_json            JSON         NULL,
                    cancel_reason           VARCHAR(512) NULL,
                    completed_at            DATETIME     NULL,
                    completed_by_staff_id   VARCHAR(32)  NULL,
                    is_deleted              TINYINT      NOT NULL DEFAULT 0,
                    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_followup_record_id (id),
                    KEY idx_followup_org_people (org_id, people_id, status),
                    KEY idx_followup_org_status (org_id, status, due_at),
                    KEY idx_followup_task (workspace_task_id),
                    KEY idx_followup_assignee (assignee_staff_id, status),
                    KEY idx_followup_type (org_id, record_type, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        ensureColumn(
                "followup_record",
                "record_type",
                "ALTER TABLE followup_record ADD COLUMN record_type VARCHAR(32) NOT NULL DEFAULT 'PERIODIC' COMMENT 'PLAN_NUDGE/METRIC_REVIEW/PERIODIC' AFTER workspace_task_id");
        dropObsoleteTable("follow_up_form");
        dropObsoleteTable("follow_up_task");
        dropObsoleteTable("alert_ticket");
        dropObsoleteTable("intervention_record");

        ensureHealthReportSchema();
    }

    /** 旧 P2 占位表 → 管理报告主表（本地空表加列即可）。 */
    private void ensureHealthReportSchema() {
        ensureTable(
                "health_report",
                """
                CREATE TABLE IF NOT EXISTS health_report (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    org_id VARCHAR(32) NOT NULL COMMENT '主管机构',
                    people_id VARCHAR(32) NOT NULL,
                    care_team_id VARCHAR(32) NULL,
                    period_type VARCHAR(16) NOT NULL COMMENT 'WEEK/MONTH/QUARTER',
                    period_start DATE NOT NULL,
                    period_end DATE NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    status VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED/SKIPPED',
                    schema_version INT NOT NULL DEFAULT 1,
                    content_json JSON NULL,
                    staff_comment VARCHAR(1000) NULL,
                    workspace_task_id VARCHAR(32) NULL,
                    published_at DATETIME NULL,
                    published_by_staff_id VARCHAR(32) NULL,
                    generated_by VARCHAR(16) NOT NULL COMMENT 'JOB/MANUAL',
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_health_report_id (id),
                    UNIQUE KEY uk_report_period (tenant_id, people_id, period_type, period_start, gmt_deleted),
                    KEY idx_report_org_status (org_id, status, period_end),
                    KEY idx_report_people_period (people_id, period_type, period_start)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理报告（周/月/季）'
                """);
        ensureColumn(
                "health_report",
                "org_id",
                "ALTER TABLE health_report ADD COLUMN org_id VARCHAR(32) NULL COMMENT '主管机构' AFTER tenant_id");
        ensureColumn(
                "health_report",
                "care_team_id",
                "ALTER TABLE health_report ADD COLUMN care_team_id VARCHAR(32) NULL AFTER people_id");
        ensureColumn(
                "health_report",
                "period_type",
                "ALTER TABLE health_report ADD COLUMN period_type VARCHAR(16) NULL COMMENT 'WEEK/MONTH/QUARTER' AFTER care_team_id");
        ensureColumn(
                "health_report",
                "period_start",
                "ALTER TABLE health_report ADD COLUMN period_start DATE NULL AFTER period_type");
        ensureColumn(
                "health_report",
                "period_end",
                "ALTER TABLE health_report ADD COLUMN period_end DATE NULL AFTER period_start");
        ensureColumn(
                "health_report",
                "status",
                "ALTER TABLE health_report ADD COLUMN status VARCHAR(16) NULL COMMENT 'DRAFT/PUBLISHED/SKIPPED' AFTER title");
        ensureColumn(
                "health_report",
                "schema_version",
                "ALTER TABLE health_report ADD COLUMN schema_version INT NOT NULL DEFAULT 1 AFTER status");
        ensureColumn(
                "health_report",
                "content_json",
                "ALTER TABLE health_report ADD COLUMN content_json JSON NULL AFTER schema_version");
        ensureColumn(
                "health_report",
                "staff_comment",
                "ALTER TABLE health_report ADD COLUMN staff_comment VARCHAR(1000) NULL AFTER content_json");
        ensureColumn(
                "health_report",
                "workspace_task_id",
                "ALTER TABLE health_report ADD COLUMN workspace_task_id VARCHAR(32) NULL AFTER staff_comment");
        ensureColumn(
                "health_report",
                "published_at",
                "ALTER TABLE health_report ADD COLUMN published_at DATETIME NULL AFTER workspace_task_id");
        ensureColumn(
                "health_report",
                "published_by_staff_id",
                "ALTER TABLE health_report ADD COLUMN published_by_staff_id VARCHAR(32) NULL AFTER published_at");
        ensureColumn(
                "health_report",
                "generated_by",
                "ALTER TABLE health_report ADD COLUMN generated_by VARCHAR(16) NULL COMMENT 'JOB/MANUAL' AFTER published_by_staff_id");
        ensureIndex(
                "health_report",
                "uk_report_period",
                "ALTER TABLE health_report ADD UNIQUE KEY uk_report_period (tenant_id, people_id, period_type, period_start, gmt_deleted)");
        ensureIndex(
                "health_report",
                "idx_report_org_status",
                "ALTER TABLE health_report ADD KEY idx_report_org_status (org_id, status, period_end)");
        ensureIndex(
                "health_report",
                "idx_report_people_period",
                "ALTER TABLE health_report ADD KEY idx_report_people_period (people_id, period_type, period_start)");
        // 旧 P2 上传元数据占位列（含 NOT NULL 的 report_date）→ 管理报告语义废弃
        dropObsoleteIndex("health_report", "idx_report_tenant_people_date");
        dropObsoleteColumn("health_report", "report_date");
        dropObsoleteColumn("health_report", "report_type");
        dropObsoleteColumn("health_report", "storage_key");
        dropObsoleteColumn("health_report", "parsed_summary_json");

        ensureTable(
                "notify_message",
                """
                CREATE TABLE IF NOT EXISTS notify_message (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    audience VARCHAR(16) NOT NULL COMMENT 'C_ACCOUNT/STAFF/OPS',
                    recipient_id VARCHAR(32) NOT NULL COMMENT '收件人账号业务ID',
                    people_id VARCHAR(32) NULL COMMENT '关联就诊人',
                    org_id VARCHAR(32) NULL,
                    event_type VARCHAR(64) NOT NULL,
                    category VARCHAR(32) NOT NULL,
                    dedupe_key VARCHAR(128) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    body VARCHAR(512) NOT NULL DEFAULT '',
                    link_path VARCHAR(256) NULL,
                    payload_json JSON NULL,
                    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                    read_at DATETIME NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_notify_message_id (id),
                    UNIQUE KEY uk_notify_message_dedupe (tenant_id, audience, recipient_id, event_type, dedupe_key, gmt_deleted),
                    KEY idx_notify_message_inbox (tenant_id, audience, recipient_id, read_at, gmt_created),
                    KEY idx_notify_message_people (tenant_id, people_id, event_type, gmt_created)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息中心逻辑消息/站内信'
                """);
        ensureTable(
                "notify_delivery",
                """
                CREATE TABLE IF NOT EXISTS notify_delivery (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    message_id VARCHAR(32) NOT NULL,
                    channel VARCHAR(32) NOT NULL COMMENT 'IN_APP/SMS/MP_SUBSCRIBE/OA_TEMPLATE',
                    status VARCHAR(16) NOT NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    next_retry_at DATETIME NULL,
                    provider VARCHAR(32) NULL,
                    provider_msg_id VARCHAR(64) NULL,
                    endpoint_snapshot VARCHAR(128) NULL,
                    error_code VARCHAR(64) NULL,
                    error_message VARCHAR(512) NULL,
                    sent_at DATETIME NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_notify_delivery_id (id),
                    UNIQUE KEY uk_notify_delivery_channel (message_id, channel),
                    KEY idx_notify_delivery_dispatch (status, next_retry_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通道投递'
                """);
        ensureTable(
                "people_assessment_snapshot",
                """
                CREATE TABLE IF NOT EXISTS people_assessment_snapshot (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    kind VARCHAR(32) NOT NULL COMMENT 'INCIDENT_RISK/SEVERITY',
                    engine_code VARCHAR(64) NOT NULL,
                    disease_code VARCHAR(64) NULL,
                    rule_pack_version VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL COMMENT 'COMPLETE/INCOMPLETE',
                    level VARCHAR(32) NULL,
                    score DECIMAL(10,2) NULL,
                    probability DECIMAL(8,4) NULL,
                    result_json JSON NULL,
                    input_snapshot_json JSON NULL,
                    trigger_source VARCHAR(16) NOT NULL COMMENT 'MANUAL/JOB',
                    assessed_at DATETIME NOT NULL,
                    assessed_by_staff_id VARCHAR(32) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_assessment_snapshot_id (id),
                    KEY idx_assessment_people_engine (tenant_id, people_id, engine_code, assessed_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患病风险/严重度评估快照'
                """);
        ensureTable(
                "care_chat_thread",
                """
                CREATE TABLE IF NOT EXISTS care_chat_thread (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    org_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    last_message_at DATETIME NULL,
                    last_message_preview VARCHAR(120) NULL,
                    last_sender_type VARCHAR(16) NULL,
                    staff_unread_count INT NOT NULL DEFAULT 0,
                    patient_unread_count INT NOT NULL DEFAULT 0,
                    closed_at DATETIME NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_chat_thread_id (id),
                    UNIQUE KEY uk_care_chat_thread_org_people (tenant_id, org_id, people_id, gmt_deleted),
                    KEY idx_care_chat_thread_people (tenant_id, people_id, last_message_at),
                    KEY idx_care_chat_thread_org_unread (tenant_id, org_id, staff_unread_count)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管沟通会话线程'
                """);
        ensureTable(
                "care_chat_message",
                """
                CREATE TABLE IF NOT EXISTS care_chat_message (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    thread_id VARCHAR(32) NOT NULL,
                    sender_type VARCHAR(16) NOT NULL,
                    sender_staff_id VARCHAR(32) NULL,
                    sender_account_id VARCHAR(32) NULL,
                    content_type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
                    content VARCHAR(2000) NOT NULL,
                    client_msg_id VARCHAR(64) NULL,
                    recalled_at DATETIME NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_care_chat_message_id (id),
                    UNIQUE KEY uk_care_chat_message_client (thread_id, client_msg_id, gmt_deleted),
                    KEY idx_care_chat_message_thread (thread_id, gmt_created),
                    KEY idx_care_chat_message_staff (tenant_id, sender_staff_id, gmt_created)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管沟通消息气泡'
                """);
    }

    private void dropObsoleteTable(String table) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """,
                Integer.class,
                table);
        if (count == null || count == 0) {
            return;
        }
        jdbcTemplate.execute("DROP TABLE IF EXISTS `" + table + "`");
        log.warn("Dropped obsolete table {}", table);
    }

    private void ensureTable(String table, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """,
                Integer.class,
                table);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.warn("Ensured table {}", table);
    }

    /** 数据兜底语句：表可能还没建出来，失败不该拦住启动。 */
    private void tryExecute(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception ex) {
            log.warn("Skip statement [{}]: {}", sql, ex.getMessage());
        }
    }

    private void ensureColumn(String table, String column, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                Integer.class,
                table,
                column);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.warn("Ensured column {}.{}", table, column);
    }

    private void ensureIndex(String table, String indexName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """,
                Integer.class,
                table,
                indexName);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbcTemplate.execute(ddl);
            log.warn("Ensured index {}.{}", table, indexName);
        } catch (Exception ex) {
            log.warn("Skip ensure index {}.{}: {}", table, indexName, ex.getMessage());
        }
    }

    private void dropObsoleteIndex(String table, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """,
                Integer.class,
                table,
                indexName);
        if (count == null || count == 0) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP INDEX `" + indexName + "`");
            log.warn("Dropped obsolete index {}.{}", table, indexName);
        } catch (Exception ex) {
            log.warn("Skip drop index {}.{}: {}", table, indexName, ex.getMessage());
        }
    }

    private void dropObsoleteColumn(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """,
                Integer.class,
                table,
                column);
        if (count == null || count == 0) {
            return;
        }
        try {
            jdbcTemplate.execute("ALTER TABLE `" + table + "` DROP COLUMN `" + column + "`");
            log.warn("Dropped obsolete column {}.{}", table, column);
        } catch (Exception ex) {
            log.warn("Skip drop column {}.{}: {}", table, column, ex.getMessage());
        }
    }
}
