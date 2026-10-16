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
                "people_profile",
                "mobile_owner",
                "ALTER TABLE people_profile ADD COLUMN mobile_owner VARCHAR(16) NULL COMMENT '号码归属：SELF/SPOUSE/CHILD/PARENT/RELATIVE/OTHER' AFTER mobile");
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

        ensureColumn(
                "adherence_daily_snapshot",
                "diet_logged_count",
                "ALTER TABLE adherence_daily_snapshot ADD COLUMN diet_logged_count INT NOT NULL DEFAULT 0 COMMENT '当日有饮食记录人数' AFTER med_rate");
        ensureColumn(
                "adherence_daily_snapshot",
                "exercise_logged_count",
                "ALTER TABLE adherence_daily_snapshot ADD COLUMN exercise_logged_count INT NOT NULL DEFAULT 0 COMMENT '当日有运动记录人数' AFTER diet_logged_count");

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
        ensureIndex(
                "workspace_task",
                "idx_workspace_task_org_done",
                "ALTER TABLE workspace_task ADD KEY idx_workspace_task_org_done (org_id, status, done_at)");
        ensureIndex(
                "workspace_task",
                "idx_workspace_task_org_opened",
                "ALTER TABLE workspace_task ADD KEY idx_workspace_task_org_opened (org_id, opened_at)");
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
        ensureIndex(
                "followup_record",
                "idx_followup_org_completed",
                "ALTER TABLE followup_record ADD KEY idx_followup_org_completed (org_id, status, completed_at)");
        ensureColumn(
                "followup_record",
                "record_type",
                "ALTER TABLE followup_record ADD COLUMN record_type VARCHAR(32) NOT NULL DEFAULT 'PERIODIC' COMMENT 'PLAN_NUDGE/METRIC_REVIEW/PERIODIC' AFTER workspace_task_id");
        dropObsoleteTable("follow_up_form");
        dropObsoleteTable("follow_up_task");
        dropObsoleteTable("alert_ticket");
        dropObsoleteTable("intervention_record");

        ensureHealthReportSchema();
        ensureRewardSchema();
        ensureMedicationSchema();
        ensureAssessmentSchema();
    }

    private void ensureAssessmentSchema() {
        ensureColumn(
                "people_assessment_snapshot",
                "tier",
                "ALTER TABLE people_assessment_snapshot ADD COLUMN tier VARCHAR(16) NULL COMMENT 'LOW/MID/HIGH/VERY_HIGH' AFTER level");
        ensureColumn(
                "people_assessment_snapshot",
                "fingerprint",
                "ALTER TABLE people_assessment_snapshot ADD COLUMN fingerprint VARCHAR(64) NULL COMMENT '结果指纹，未变化不新增快照' AFTER input_snapshot_json");
        tryExecute("UPDATE people_assessment_snapshot SET kind = 'RISK' WHERE kind = 'INCIDENT_RISK'");
        tryExecute("UPDATE people_assessment_snapshot SET kind = 'SEVERITY' WHERE kind = 'CONTROL_STATUS'");
        tryExecute("UPDATE people_assessment_snapshot SET engine_code = 'OBESITY_RISK' WHERE engine_code = 'OBESITY_SCREEN'");
        tryExecute("UPDATE people_assessment_snapshot SET engine_code = 'DIABETES_SEVERITY' WHERE engine_code = 'DIABETES_CONTROL_LABEL'");
        rewriteAssessmentAdvice();
        ensureTable(
                "people_assessment_latest",
                """
                CREATE TABLE IF NOT EXISTS people_assessment_latest (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    engine_code VARCHAR(64) NOT NULL,
                    category VARCHAR(16) NOT NULL,
                    disease_code VARCHAR(32) NOT NULL,
                    snapshot_id VARCHAR(32) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    level VARCHAR(32) NULL,
                    level_label VARCHAR(64) NULL,
                    tier VARCHAR(16) NULL,
                    score DECIMAL(10,2) NULL,
                    active TINYINT NOT NULL DEFAULT 1,
                    fingerprint VARCHAR(64) NULL,
                    assessed_at DATETIME NOT NULL,
                    evaluated_at DATETIME NOT NULL,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_assessment_latest (people_id, engine_code),
                    KEY idx_assessment_latest_tenant (tenant_id, people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每人每引擎最新评估结果'
                """);
        ensureTable(
                "people_assessment_state",
                """
                CREATE TABLE IF NOT EXISTS people_assessment_state (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    dirty_at DATETIME NULL,
                    evaluated_at DATETIME NULL,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_assessment_state_people (people_id),
                    KEY idx_assessment_state_dirty (dirty_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估待重算标记'
                """);
        ensureTable(
                "people_assessment_fact",
                """
                CREATE TABLE IF NOT EXISTS people_assessment_fact (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    field_code VARCHAR(64) NOT NULL,
                    value_json JSON NOT NULL,
                    source VARCHAR(32) NOT NULL COMMENT 'QUESTIONNAIRE_STAFF/QUESTIONNAIRE_PATIENT',
                    submission_id VARCHAR(32) NULL,
                    recorded_at DATETIME NOT NULL,
                    recorded_by VARCHAR(32) NULL,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_assessment_fact_id (id),
                    UNIQUE KEY uk_assessment_fact_field (people_id, field_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估问卷补充事实（档案外字段，仅存最新值）'
                """);
        ensureTable(
                "assessment_questionnaire_submission",
                """
                CREATE TABLE IF NOT EXISTS assessment_questionnaire_submission (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    portal VARCHAR(8) NOT NULL COMMENT 'B/C',
                    submitted_by VARCHAR(32) NULL COMMENT 'staffId / accountId',
                    request_id VARCHAR(32) NULL,
                    answers_json JSON NOT NULL,
                    overrides_json JSON NULL COMMENT '确认覆盖的档案冲突项',
                    submitted_at DATETIME NOT NULL,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_assessment_submission_id (id),
                    KEY idx_assessment_submission_people (tenant_id, people_id, submitted_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估问卷提交记录'
                """);
        ensureTable(
                "assessment_questionnaire_request",
                """
                CREATE TABLE IF NOT EXISTS assessment_questionnaire_request (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    org_id VARCHAR(32) NULL,
                    staff_id VARCHAR(32) NOT NULL,
                    fields_json JSON NOT NULL,
                    status VARCHAR(16) NOT NULL COMMENT 'PENDING/SUBMITTED/EXPIRED/CANCELLED',
                    expires_at DATETIME NOT NULL,
                    reminded_at DATETIME NULL,
                    submission_id VARCHAR(32) NULL,
                    submitted_at DATETIME NULL,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_assessment_request_id (id),
                    KEY idx_assessment_request_people (people_id, status),
                    KEY idx_assessment_request_status (status, expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管师发给患者的评估问卷'
                """);
    }

    private void ensureMedicationSchema() {
        ensureColumn(
                "people_medication",
                "drug_code",
                "ALTER TABLE people_medication ADD COLUMN drug_code VARCHAR(64) NULL COMMENT '药品目录编码' AFTER drug_name");
        ensureColumn(
                "people_medication",
                "slots_json",
                "ALTER TABLE people_medication ADD COLUMN slots_json VARCHAR(128) NULL COMMENT '服药时段覆盖' AFTER status");
        ensureColumn(
                "people_medication",
                "verified_at",
                "ALTER TABLE people_medication ADD COLUMN verified_at DATETIME NULL COMMENT '自添加药核对时间' AFTER slots_json");
        ensureColumn(
                "people_medication",
                "verified_by_staff_id",
                "ALTER TABLE people_medication ADD COLUMN verified_by_staff_id VARCHAR(32) NULL COMMENT '核对人' AFTER verified_at");
        ensureColumn(
                "people_medication",
                "allergy_conflict",
                "ALTER TABLE people_medication ADD COLUMN allergy_conflict VARCHAR(255) NULL COMMENT '药物过敏冲突摘要' AFTER verified_by_staff_id");
        ensureColumn(
                "people_medication",
                "stop_reason",
                "ALTER TABLE people_medication ADD COLUMN stop_reason VARCHAR(32) NULL COMMENT '患者自述停药原因' AFTER allergy_conflict");
        ensureColumn(
                "people_medication_intake",
                "dose_seq",
                "ALTER TABLE people_medication_intake ADD COLUMN dose_seq TINYINT NOT NULL DEFAULT 1 COMMENT 'PRN 同日第几次' AFTER time_slot");
        ensureColumn(
                "people_medication_intake",
                "taken_at",
                "ALTER TABLE people_medication_intake ADD COLUMN taken_at DATETIME NULL COMMENT '实际服药时刻' AFTER status");
        ensureColumn(
                "people_medication_intake",
                "backfill",
                "ALTER TABLE people_medication_intake ADD COLUMN backfill TINYINT NOT NULL DEFAULT 0 COMMENT '1=次日补记' AFTER taken_at");
        ensureIndex(
                "people_medication_intake",
                "uk_med_intake_day_slot_seq",
                "ALTER TABLE people_medication_intake ADD UNIQUE KEY uk_med_intake_day_slot_seq (medication_id, intake_date, time_slot, dose_seq, gmt_deleted)");
        dropObsoleteIndex("people_medication_intake", "uk_med_intake_day_slot");
        ensureTable(
                "people_medication_reminder",
                """
                CREATE TABLE IF NOT EXISTS people_medication_reminder (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    morning_at CHAR(5) NOT NULL DEFAULT '08:00',
                    noon_at CHAR(5) NOT NULL DEFAULT '12:00',
                    evening_at CHAR(5) NOT NULL DEFAULT '18:00',
                    bedtime_at CHAR(5) NOT NULL DEFAULT '21:30',
                    follow_up_min INT NOT NULL DEFAULT 30,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_med_reminder_id (id),
                    UNIQUE KEY uk_med_reminder_people (tenant_id, people_id, gmt_deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服药提醒设置'
                """);
        ensureTable(
                "people_medication_reminder_log",
                """
                CREATE TABLE IF NOT EXISTS people_medication_reminder_log (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    remind_date DATE NOT NULL,
                    time_slot VARCHAR(16) NOT NULL,
                    seq TINYINT NOT NULL,
                    sent_at DATETIME NOT NULL,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_med_remind (people_id, remind_date, time_slot, seq)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服药提醒发送去重'
                """);
        ensureTable(
                "people_medication_feedback",
                """
                CREATE TABLE IF NOT EXISTS people_medication_feedback (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    medication_id VARCHAR(32) NOT NULL,
                    kind VARCHAR(24) NOT NULL,
                    symptoms_json VARCHAR(255) NULL,
                    severity VARCHAR(16) NULL,
                    content VARCHAR(500) NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
                    work_task_id VARCHAR(32) NULL,
                    handled_by_staff_id VARCHAR(32) NULL,
                    handled_at DATETIME NULL,
                    reply VARCHAR(500) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_med_feedback_id (id),
                    KEY idx_med_feedback_people (tenant_id, people_id, status),
                    KEY idx_med_feedback_med (medication_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者用药反馈'
                """);
    }

    private void ensureRewardSchema() {
        ensureTable(
                "reward_point_ledger",
                """
                CREATE TABLE IF NOT EXISTS reward_point_ledger (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    biz_date DATE NOT NULL,
                    biz_type VARCHAR(16) NOT NULL,
                    biz_ref VARCHAR(64) NOT NULL,
                    seq INT NOT NULL DEFAULT 0,
                    points INT NOT NULL,
                    title VARCHAR(64) NOT NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_reward_ledger_id (id),
                    UNIQUE KEY uk_reward_ledger_ref (people_id, biz_type, biz_ref, seq),
                    KEY idx_reward_ledger_people_date (people_id, biz_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康豆流水'
                """);
        ensureTable(
                "reward_summary",
                """
                CREATE TABLE IF NOT EXISTS reward_summary (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    level_max INT NOT NULL DEFAULT 1,
                    streak_days INT NOT NULL DEFAULT 0,
                    best_streak INT NOT NULL DEFAULT 0,
                    freeze_cards INT NOT NULL DEFAULT 0,
                    last_settled_date DATE NULL,
                    last_push_date DATE NULL,
                    reward_disabled TINYINT NOT NULL DEFAULT 0,
                    peer_opt_out TINYINT NOT NULL DEFAULT 0,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_reward_summary_id (id),
                    UNIQUE KEY uk_reward_summary_people (people_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者激励汇总'
                """);
        ensureColumn(
                "reward_summary",
                "peer_opt_out",
                "ALTER TABLE reward_summary ADD COLUMN peer_opt_out TINYINT NOT NULL DEFAULT 0 COMMENT '关闭同期百分位对比' AFTER reward_disabled");
        ensureTable(
                "reward_day",
                """
                CREATE TABLE IF NOT EXISTS reward_day (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    biz_date DATE NOT NULL,
                    active TINYINT NOT NULL DEFAULT 0,
                    full_day TINYINT NOT NULL DEFAULT 0,
                    due_count INT NOT NULL DEFAULT 0,
                    done_count INT NOT NULL DEFAULT 0,
                    freeze_used TINYINT NOT NULL DEFAULT 0,
                    streak_after INT NOT NULL DEFAULT 0,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_reward_day_id (id),
                    UNIQUE KEY uk_reward_day_people (people_id, biz_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='激励日结'
                """);
        ensureTable(
                "reward_badge",
                """
                CREATE TABLE IF NOT EXISTS reward_badge (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    badge_code VARCHAR(64) NOT NULL,
                    badge_name VARCHAR(64) NOT NULL,
                    kind VARCHAR(16) NOT NULL,
                    earned_date DATE NOT NULL,
                    source_ref VARCHAR(64) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_reward_badge_id (id),
                    UNIQUE KEY uk_reward_badge_people (people_id, badge_code)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者徽章'
                """);
        ensureTable(
                "reward_kudos",
                """
                CREATE TABLE IF NOT EXISTS reward_kudos (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    org_id VARCHAR(32) NULL,
                    people_id VARCHAR(32) NOT NULL,
                    staff_id VARCHAR(32) NOT NULL,
                    kudos_date DATE NOT NULL,
                    message VARCHAR(100) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_reward_kudos_id (id),
                    UNIQUE KEY uk_reward_kudos_day (people_id, staff_id, kudos_date),
                    KEY idx_reward_kudos_people (people_id, gmt_created)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管师点赞'
                """);
        ensureTable(
                "reward_challenge",
                """
                CREATE TABLE IF NOT EXISTS reward_challenge (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    period_start DATE NOT NULL,
                    period_end DATE NOT NULL,
                    kind VARCHAR(32) NOT NULL,
                    title VARCHAR(64) NOT NULL,
                    target INT NOT NULL,
                    progress INT NOT NULL DEFAULT 0,
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    source VARCHAR(16) NOT NULL DEFAULT 'AUTO',
                    points_awarded INT NOT NULL DEFAULT 0,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_reward_challenge_id (id),
                    UNIQUE KEY uk_reward_challenge_week (people_id, period_start, kind),
                    KEY idx_reward_challenge_people (people_id, period_start)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周挑战'
                """);
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
                    patient_read_at DATETIME NULL COMMENT 'C 端首次阅读时间',
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
                "patient_read_at",
                "ALTER TABLE health_report ADD COLUMN patient_read_at DATETIME NULL COMMENT 'C 端首次阅读时间' AFTER published_by_staff_id");
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
        ensureIndex(
                "health_report",
                "idx_report_org_published",
                "ALTER TABLE health_report ADD KEY idx_report_org_published (org_id, status, published_at)");
        // 报告已结案但审阅单仍 OPEN：按 bizKey=reportId 兜底办结
        tryExecute(
                """
                UPDATE workspace_task t
                INNER JOIN health_report r
                  ON r.id = t.biz_key
                 AND r.is_deleted = 0
                 AND r.status IN ('PUBLISHED', 'SKIPPED')
                SET t.status = 'DONE',
                    t.close_reason = 'FORM',
                    t.done_at = COALESCE(t.done_at, r.published_at, NOW()),
                    t.done_by_staff_id = COALESCE(t.done_by_staff_id, r.published_by_staff_id, t.assignee_staff_id),
                    t.gmt_modified = NOW()
                WHERE t.task_type = 'REPORT_REVIEW'
                  AND t.status = 'OPEN'
                  AND t.is_deleted = 0
                """);
        // 回写报告上缺失的 workspace_task_id，便于后续发布/跳过走主键关单
        tryExecute(
                """
                UPDATE health_report r
                INNER JOIN workspace_task t
                  ON t.biz_key = r.id
                 AND t.task_type = 'REPORT_REVIEW'
                 AND t.is_deleted = 0
                 AND t.tenant_id = r.tenant_id
                 AND t.org_id = r.org_id
                SET r.workspace_task_id = t.id,
                    r.gmt_modified = NOW()
                WHERE r.is_deleted = 0
                  AND (r.workspace_task_id IS NULL OR r.workspace_task_id = '')
                """);
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
        ensureTable(
                "followup_schedule_rule",
                """
                CREATE TABLE IF NOT EXISTS followup_schedule_rule (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    org_id VARCHAR(32) NOT NULL DEFAULT '',
                    disease_code VARCHAR(64) NOT NULL DEFAULT '',
                    interval_with_plan INT NOT NULL,
                    interval_no_plan INT NOT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    updated_by_staff_id VARCHAR(32) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_followup_rule_id (id),
                    UNIQUE KEY uk_followup_rule_scope (tenant_id, org_id, disease_code, gmt_deleted)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定期随访排期规则'
                """);
        ensureTable(
                "web_push_subscription",
                """
                CREATE TABLE IF NOT EXISTS web_push_subscription (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    account_id VARCHAR(32) NOT NULL,
                    endpoint VARCHAR(1024) NOT NULL,
                    endpoint_hash CHAR(64) NOT NULL,
                    p256dh VARCHAR(128) NOT NULL,
                    auth VARCHAR(64) NOT NULL,
                    user_agent VARCHAR(255) NULL,
                    fail_count INT NOT NULL DEFAULT 0,
                    last_success_at DATETIME NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_web_push_sub_id (id),
                    UNIQUE KEY uk_web_push_sub_endpoint (endpoint_hash),
                    KEY idx_web_push_sub_account (account_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='浏览器推送订阅'
                """);
        ensureTable(
                "web_push_vapid",
                """
                CREATE TABLE IF NOT EXISTS web_push_vapid (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    public_key VARCHAR(128) NOT NULL,
                    private_key VARCHAR(128) NOT NULL,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_web_push_vapid_id (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Web Push VAPID 密钥'
                """);
        ensureTable(
                "water_intake_log",
                """
                CREATE TABLE IF NOT EXISTS water_intake_log (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    biz_date DATE NOT NULL,
                    amount_ml INT NOT NULL,
                    source VARCHAR(16) NOT NULL DEFAULT 'CUP',
                    recorded_at DATETIME NULL,
                    recorded_by_people_id VARCHAR(32) NULL,
                    recorded_by_staff_id VARCHAR(32) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_water_intake_id (id),
                    KEY idx_water_intake_people_date (people_id, biz_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='饮水记录'
                """);
        ensureColumn(
                "water_intake_log",
                "recorded_at",
                "ALTER TABLE water_intake_log ADD COLUMN recorded_at DATETIME NULL AFTER source");
        jdbcTemplate.execute(
                "UPDATE water_intake_log SET recorded_at = gmt_created WHERE recorded_at IS NULL AND is_deleted = 0");
        ensureTable(
                "sleep_log",
                """
                CREATE TABLE IF NOT EXISTS sleep_log (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    biz_date DATE NOT NULL,
                    segment VARCHAR(16) NOT NULL DEFAULT 'MAIN',
                    duration_min INT NOT NULL,
                    quality VARCHAR(16) NOT NULL DEFAULT 'FAIR',
                    bedtime TIME NULL,
                    wake_time TIME NULL,
                    note VARCHAR(200) NULL,
                    recorded_by_people_id VARCHAR(32) NULL,
                    recorded_by_staff_id VARCHAR(32) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_sleep_log_id (id),
                    KEY idx_sleep_log_people_date (people_id, biz_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='睡眠记录'
                """);
        ensureColumn(
                "sleep_log",
                "segment",
                "ALTER TABLE sleep_log ADD COLUMN segment VARCHAR(16) NOT NULL DEFAULT 'MAIN' AFTER biz_date");
        // 多段睡眠：去掉「一人一天一条」唯一约束
        dropObsoleteIndex("sleep_log", "uk_sleep_log_people_date");
        ensureTable(
                "mood_log",
                """
                CREATE TABLE IF NOT EXISTS mood_log (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    people_id VARCHAR(32) NOT NULL,
                    biz_date DATE NOT NULL,
                    score TINYINT NOT NULL,
                    tags_json JSON NULL,
                    note VARCHAR(200) NULL,
                    recorded_by_people_id VARCHAR(32) NULL,
                    recorded_by_staff_id VARCHAR(32) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_mood_log_id (id),
                    KEY idx_mood_log_people_date (people_id, biz_date)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心情记录'
                """);
        ensureTable(
                "org_recipe",
                """
                CREATE TABLE IF NOT EXISTS org_recipe (
                    pk_id BIGINT NOT NULL AUTO_INCREMENT,
                    id VARCHAR(32) NOT NULL,
                    tenant_id VARCHAR(32) NOT NULL,
                    org_id VARCHAR(32) NOT NULL,
                    name VARCHAR(64) NOT NULL,
                    description VARCHAR(255) NULL,
                    tags_json JSON NULL,
                    meal_json JSON NOT NULL,
                    week_json JSON NULL,
                    energy_kcal_hint INT NULL,
                    allergen_keywords_json JSON NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
                    created_by_staff_id VARCHAR(32) NULL,
                    updated_by_staff_id VARCHAR(32) NULL,
                    is_deleted TINYINT NOT NULL DEFAULT 0,
                    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
                    PRIMARY KEY (pk_id),
                    UNIQUE KEY uk_org_recipe_id (id),
                    KEY idx_org_recipe_org (org_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构食谱库'
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

    /** 评估建议文案不得含启药、调药、手术等诊疗指令；已存快照按旧文案片段替换（新文案不含旧片段，可重复执行）。 */
    private void rewriteAssessmentAdvice() {
        String[][] pairs = {
            {"心血管风险很高危，建议立即启动或强化药物治疗，并积极处理并存的危险因素与临床疾病",
                "心血管风险分层为很高危。建议优先安排医师复诊，由医师评估降压方案；加强血压监测，协助管理并存的危险因素"},
            {"心血管风险高危，建议及时启动降压药物治疗，同时控制其他危险因素",
                "心血管风险分层为高危。建议安排医师复诊评估降压方案，同时加强血压监测与危险因素管理"},
            {"心血管风险中危，建议生活方式干预并密切随访血压，必要时启动药物治疗",
                "心血管风险分层为中危。建议生活方式干预并加强血压随访，按医师意见复诊"},
            {"极重度肥胖，建议转诊专科评估药物或代谢手术治疗",
                "肥胖分层为极重度。建议安排专科医师评估后续干预方案，持续开展营养与运动管理"},
            {"重度肥胖或已有终末器官损害，建议专科评估，综合药物、营养与运动干预",
                "肥胖分层为重度或已有终末器官损害。建议安排专科医师评估，同时加强营养、运动与合并症随访"},
            {"建议强化减重（目标 3–6 个月减重 5%–10%）并管理合并症",
                "建议强化生活方式减重（目标 3–6 个月减重 5%–10%），关注合并症随访"},
            {"心脑血管病风险高危，建议尽快就医", "心脑血管病风险高危，建议尽快安排医师就诊"},
            {"建议立即就医，启动药物治疗", "建议尽快安排医师就诊"},
            {"强烈建议行口服葡萄糖耐量试验（OGTT）排查是否已患病", "建议安排医师评估是否进行口服葡萄糖耐量试验（OGTT）"},
            {"强烈建议做口服葡萄糖耐量试验（OGTT）", "建议安排医师评估是否进行口服葡萄糖耐量试验（OGTT）"},
            {"立即就医，紧急干预", "建议尽快安排医师复诊，加强血糖监测"},
            {"建议就医复评，加强监测", "建议安排医师复评，加强血糖监测"},
        };
        for (String[] p : pairs) {
            try {
                int n = jdbcTemplate.update(
                        """
                        UPDATE people_assessment_snapshot
                        SET result_json = JSON_SET(result_json, '$.advice',
                            REPLACE(JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.advice')), ?, ?))
                        WHERE JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.advice')) LIKE CONCAT('%', ?, '%')
                        """,
                        p[0], p[1], p[0]);
                if (n > 0) {
                    log.warn("Rewrote assessment advice x{}: {}", n, p[0]);
                }
            } catch (Exception ex) {
                log.warn("Skip assessment advice rewrite [{}]: {}", p[0], ex.getMessage());
            }
        }
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
