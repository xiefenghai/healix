-- Healix schema v0.5
-- 约定：pk_id 物理自增主键；id 业务主键 VARCHAR(32)=SnowflakeId.nextBizId()；
-- 表间关联一律用业务 id；is_deleted / gmt_created / gmt_modified / gmt_deleted 全表统一。
-- MySQL 8.0+, database: healix
--
-- 启动时仅 CREATE TABLE IF NOT EXISTS，不删表、不清数据。
-- 需要从零重来时请手动执行 schema-reset.sql，再重启应用。

SET NAMES utf8mb4;

-- =============================================================================
-- P1: 平台 / 租户 / 机构
-- =============================================================================

CREATE TABLE IF NOT EXISTS tenant (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    code            VARCHAR(64)  NOT NULL COMMENT '租户编码（唯一）',
    name            VARCHAR(128) NOT NULL COMMENT '租户名称',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/SUSPENDED/CLOSED',
    plan_code       VARCHAR(64)  NULL COMMENT '套餐编码',
    expire_at       DATETIME     NULL COMMENT '套餐/租约到期时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_tenant_id (id),
    UNIQUE KEY uk_tenant_code (code, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户（PHI 共享边界）';

CREATE TABLE IF NOT EXISTS organization (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    parent_org_id       VARCHAR(32)  NULL COMMENT '上级机构业务ID（预留树形）',
    name            VARCHAR(128) NOT NULL COMMENT '机构名称',
    org_code        VARCHAR(32)  NOT NULL COMMENT '机构编码（01 开头 32 位）',
    org_type        VARCHAR(32)  NULL COMMENT '机构类型',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_organization_id (id),
    UNIQUE KEY uk_org_code (org_code, gmt_deleted),
    KEY idx_org_tenant (tenant_id),
    KEY idx_org_parent (parent_org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构（协作任务/统计边界）';

CREATE TABLE IF NOT EXISTS org_invite_code (
    pk_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    code                 VARCHAR(32)  NOT NULL COMMENT '邀请码',
    enabled              TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
    expire_at            DATETIME     NOT NULL COMMENT '过期时间',
    created_by_staff_id VARCHAR(32)  NULL COMMENT '创建人 staff_profile.id',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted          DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_org_invite_code_id (id),
    UNIQUE KEY uk_invite_code (code, gmt_deleted),
    KEY idx_invite_org (org_id),
    KEY idx_invite_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构患者邀请码';

-- =============================================================================
-- P1: 三端账号
-- =============================================================================

CREATE TABLE IF NOT EXISTS ops_account (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    username        VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    password_hash   VARCHAR(128) NOT NULL COMMENT '密码哈希',
    display_name    VARCHAR(64)  NULL COMMENT '显示名称',
    role_code       VARCHAR(32)  NOT NULL COMMENT '平台角色：SUPER_ADMIN/OPERATOR/AUDITOR',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_ops_account_id (id),
    UNIQUE KEY uk_ops_username (username, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Ops 平台运维账号';

CREATE TABLE IF NOT EXISTS staff_account (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    username        VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    password_hash   VARCHAR(128) NOT NULL COMMENT '密码哈希',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_staff_account_id (id),
    UNIQUE KEY uk_staff_username (username, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B 端登录账号';

CREATE TABLE IF NOT EXISTS account_mfa (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    account_type        VARCHAR(16)  NOT NULL COMMENT '账号类型：STAFF/OPS',
    account_id          VARCHAR(32)  NOT NULL COMMENT '账号业务ID',
    secret          VARCHAR(64)  NOT NULL COMMENT 'Base32 TOTP 密钥',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/ENABLED',
    enabled_at      DATETIME     NULL COMMENT '启用时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_account_mfa_id (id),
    UNIQUE KEY uk_account_mfa (account_type, account_id, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B/Ops 账号二次校验绑定';

CREATE TABLE IF NOT EXISTS staff_profile (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花，staffId）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    account_id          VARCHAR(32)  NOT NULL COMMENT '关联 staff_account.id',
    display_name    VARCHAR(64)  NULL COMMENT '显示名称',
    mobile          VARCHAR(32)  NULL COMMENT '手机号',
    title           VARCHAR(64)  NULL COMMENT '职称/头衔',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_staff_profile_id (id),
    UNIQUE KEY uk_staff_account (account_id, gmt_deleted),
    KEY idx_staff_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='B 端人员档案（归属租户）';

CREATE TABLE IF NOT EXISTS staff_org_binding (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    staff_id            VARCHAR(32)  NOT NULL COMMENT '人员业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '机构业务ID',
    is_default      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认工作机构：1是 0否',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_staff_org_binding_id (id),
    UNIQUE KEY uk_staff_org (staff_id, org_id, gmt_deleted),
    KEY idx_staff_org_tenant (tenant_id),
    KEY idx_staff_org_org (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员-机构绑定';

CREATE TABLE IF NOT EXISTS staff_role_binding (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    staff_id            VARCHAR(32)  NOT NULL COMMENT '人员业务ID',
    role_code       VARCHAR(32)  NOT NULL COMMENT '角色编码',
    org_id              VARCHAR(32)  NULL COMMENT '角色作用机构；功能角色恒空',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_staff_role_binding_id (id),
    UNIQUE KEY uk_staff_role_org (staff_id, role_code, org_id, gmt_deleted),
    KEY idx_staff_role_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员-角色绑定';

CREATE TABLE IF NOT EXISTS staff_patient_watch (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '机构业务ID',
    staff_id            VARCHAR(32)  NOT NULL COMMENT '关注人 staff_profile.id',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者 people_profile.id',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_staff_patient_watch_id (id),
    UNIQUE KEY uk_staff_patient_watch (staff_id, org_id, people_id, gmt_deleted),
    KEY idx_staff_patient_watch_org_staff (org_id, staff_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工个人重点关注患者';

-- =============================================================================
-- 患者 people_*
-- =============================================================================

CREATE TABLE IF NOT EXISTS people_account (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    username        VARCHAR(64)  NOT NULL COMMENT '登录用户名（租户内唯一）',
    password_hash   VARCHAR(128) NOT NULL COMMENT '密码哈希',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_account_id (id),
    UNIQUE KEY uk_people_account_tenant_username (tenant_id, username, gmt_deleted),
    KEY idx_people_account_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C 端登录账号（租户隔离）';

CREATE TABLE IF NOT EXISTS people_profile (
    pk_id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花，people_id）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    account_id          VARCHAR(32)  NULL COMMENT '关联 people_account.id；可空',
    display_name           VARCHAR(64)  NOT NULL COMMENT '姓名',
    gender                 VARCHAR(16)  NULL COMMENT '性别：MALE/FEMALE/UNKNOWN',
    birthday               DATE         NULL COMMENT '出生日期',
    name_pinyin            VARCHAR(128) NULL COMMENT '姓名拼音',
    mobile                 VARCHAR(32)  NULL COMMENT '联系手机号',
    mobile_owner           VARCHAR(16)  NULL COMMENT '号码归属：SELF/SPOUSE/CHILD/PARENT/RELATIVE/OTHER',
    address                VARCHAR(256) NULL COMMENT '家庭住址',
    education_level        VARCHAR(32)  NULL COMMENT '文化程度',
    marital_status         VARCHAR(32)  NULL COMMENT '婚姻状况',
    occupation             VARCHAR(64)  NULL COMMENT '职业',
    allergens_json         JSON         NULL COMMENT '过敏原 JSON',
    chronic_tags_json      JSON         NULL COMMENT '慢性病标签 JSON',
    emergency_contact_json JSON         NULL COMMENT '紧急联系人 JSON',
    merged_into_people_id  VARCHAR(32)  NULL COMMENT '已合并到的目标 people_id；非空表示合并留痕',
    is_deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted            DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_profile_id (id),
    UNIQUE KEY uk_people_account (account_id, gmt_deleted),
    KEY idx_people_tenant_name (tenant_id, display_name),
    KEY idx_people_tenant_pinyin (tenant_id, name_pinyin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者基础信息（租户隔离）';

CREATE TABLE IF NOT EXISTS people_identity (
    pk_id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    identity_type          VARCHAR(32)  NOT NULL COMMENT '证件类型',
    identity_value_hash    VARCHAR(64)  NOT NULL COMMENT '证件号哈希',
    identity_value_cipher  VARCHAR(256) NOT NULL COMMENT '证件号密文',
    identity_value_mask    VARCHAR(64)  NOT NULL COMMENT '证件号脱敏',
    is_deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted            DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_identity_id (id),
    UNIQUE KEY uk_people_identity_tenant_type_hash (tenant_id, identity_type, identity_value_hash, gmt_deleted),
    UNIQUE KEY uk_people_identity_people_type (people_id, identity_type, gmt_deleted),
    KEY idx_people_identity_people (people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者身份证件';

CREATE TABLE IF NOT EXISTS account_patient (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    account_id              VARCHAR(32)  NOT NULL COMMENT 'C 端账号业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '自然人 people_id',
    display_name            VARCHAR(64)  NOT NULL COMMENT '卡片展示姓名',
    relation                VARCHAR(32)  NULL COMMENT '关系：SELF/PARENT/CHILD/SPOUSE/OTHER',
    identity_type           VARCHAR(32)  NULL COMMENT '证件类型',
    identity_value_hash     VARCHAR(64)  NULL COMMENT '证件号哈希（账号内唯一）',
    identity_value_mask     VARCHAR(64)  NULL COMMENT '证件号脱敏',
    mobile                  VARCHAR(32)  NULL COMMENT '手机（Phase1 只存不验）',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_account_patient_id (id),
    UNIQUE KEY uk_account_patient_identity (account_id, identity_type, identity_value_hash, gmt_deleted),
    KEY idx_account_patient_account (account_id),
    KEY idx_account_patient_people (people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='C 端账号就诊人卡片';

CREATE TABLE IF NOT EXISTS people_activation_invite (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NOT NULL COMMENT '目标机构',
    people_id               VARCHAR(32)  NOT NULL COMMENT '待激活自然人',
    code                    VARCHAR(32)  NOT NULL COMMENT '激活码',
    enabled                 TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    expire_at               DATETIME     NULL COMMENT '过期时间',
    used_at                 DATETIME     NULL COMMENT '使用时间',
    used_by_account_id      VARCHAR(32)  NULL COMMENT '消费账号',
    created_by_staff_id     VARCHAR(32)  NULL COMMENT '发码人',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_activation_invite_id (id),
    UNIQUE KEY uk_people_activation_invite_code (code, gmt_deleted),
    KEY idx_people_activation_invite_people (people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者专属激活码';

CREATE TABLE IF NOT EXISTS people_org_membership (
    pk_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '机构业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    status               VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/LEFT',
    joined_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入组时间',
    left_at              DATETIME     NULL COMMENT '退出时间',
    invited_by_staff_id VARCHAR(32)  NULL COMMENT '邀请人 staff_profile.id',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted          DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_org_membership_id (id),
    UNIQUE KEY uk_membership_org_people (org_id, people_id, gmt_deleted),
    KEY idx_membership_tenant_people (tenant_id, people_id),
    KEY idx_membership_org_status (org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者-机构会员关系';

CREATE TABLE IF NOT EXISTS people_care_assignment (
    pk_id                           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    primary_org_id      VARCHAR(32)  NOT NULL COMMENT '主管机构业务ID',
    primary_care_manager_staff_id VARCHAR(32)  NULL COMMENT '主管健管师业务ID',
    is_deleted                      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created                     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified                    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted                     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_care_assignment_id (id),
    UNIQUE KEY uk_care_tenant_people (tenant_id, people_id, gmt_deleted),
    KEY idx_care_primary_org (primary_org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者主管机构/主管健管师';

CREATE TABLE IF NOT EXISTS people_basic_archive (
    pk_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                   VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id            VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id            VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    schema_version       VARCHAR(16)  NOT NULL DEFAULT '1.0' COMMENT '档案 schema 版本',
    content_json         JSON         NULL COMMENT '基础档案全文',
    version              INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
    source               VARCHAR(32)  NOT NULL DEFAULT 'B_SIDE' COMMENT '来源：B_SIDE/C_JOIN',
    created_by_staff_id  VARCHAR(32)  NULL COMMENT '建档人业务ID',
    updated_by_staff_id VARCHAR(32) NULL COMMENT '最后修改人',
    remark               VARCHAR(256) NULL COMMENT '备注',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted          DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_basic_archive_id (id),
    UNIQUE KEY uk_basic_archive_tenant_people (tenant_id, people_id, gmt_deleted),
    KEY idx_basic_archive_people (people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者基础档案（租户级）';

CREATE TABLE IF NOT EXISTS people_disease_archive (
    pk_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                   VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id            VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id            VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    disease_code         VARCHAR(64)  NOT NULL COMMENT '病种编码',
    schema_version       VARCHAR(16)  NOT NULL DEFAULT '1.0',
    content_json         JSON         NULL COMMENT '病种档案全文',
    version              INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
    source               VARCHAR(32)  NOT NULL DEFAULT 'B_SIDE',
    created_by_staff_id  VARCHAR(32)  NULL,
    updated_by_staff_id  VARCHAR(32)  NULL,
    is_deleted           TINYINT      NOT NULL DEFAULT 0,
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted          DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_disease_archive_id (id),
    UNIQUE KEY uk_disease_archive_tenant_people_code (tenant_id, people_id, disease_code, gmt_deleted),
    KEY idx_disease_archive_people (people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者病种档案（租户级）';

CREATE TABLE IF NOT EXISTS sys_dict (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键',
    id              VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    dict_type       VARCHAR(32)  NOT NULL COMMENT 'OPTION/FIELD/DISEASE/DISEASE_FIELD',
    parent_code     VARCHAR(64)  NOT NULL DEFAULT '0' COMMENT '父级 code，0=顶级',
    dict_code       VARCHAR(64)  NOT NULL COMMENT '字典编码',
    dict_code_desc  VARCHAR(128) NOT NULL COMMENT '中文描述',
    content         JSON         NULL COMMENT '扩展配置',
    sort_order      INT          NOT NULL DEFAULT 0,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT '0' COMMENT '0=平台通用',
    is_deleted      TINYINT      NOT NULL DEFAULT 0,
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_dict_id (id),
    UNIQUE KEY uk_dict_tenant_type_parent_code (tenant_id, dict_type, parent_code, dict_code, gmt_deleted),
    KEY idx_dict_parent (tenant_id, dict_type, parent_code, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统通用字典';

CREATE TABLE IF NOT EXISTS people_metadata_info (
    pk_id              BIGINT       NOT NULL AUTO_INCREMENT,
    id                 VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id          VARCHAR(32)  NOT NULL,
    people_id          VARCHAR(32)  NOT NULL,
    metadata_code      VARCHAR(100) NOT NULL,
    metadata_value     JSON         NOT NULL,
    source_biz_code    VARCHAR(64)  NULL,
    source_client_code VARCHAR(64)  NULL,
    is_deleted         TINYINT      NOT NULL DEFAULT 0,
    gmt_created        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted        DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_meta_id (id),
    UNIQUE KEY uk_meta_people_code (people_id, metadata_code, gmt_deleted),
    KEY idx_meta_tenant_people (tenant_id, people_id),
    KEY idx_meta_tenant_code (tenant_id, metadata_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者元数据（租户级）';

CREATE TABLE IF NOT EXISTS field_revision_batch (
    pk_id             BIGINT       NOT NULL AUTO_INCREMENT,
    id                VARCHAR(32)  NOT NULL,
    tenant_id         VARCHAR(32)  NOT NULL,
    target_people_id  VARCHAR(32)  NOT NULL,
    operator_type     VARCHAR(32)  NOT NULL COMMENT 'STAFF/PEOPLE/SYSTEM',
    operator_id       VARCHAR(32)  NOT NULL,
    biz_type          VARCHAR(64)  NOT NULL,
    biz_key           VARCHAR(64)  NULL,
    version_before    INT          NOT NULL,
    version_after     INT          NOT NULL,
    org_id            VARCHAR(32)  NULL COMMENT '操作上下文机构',
    is_deleted        TINYINT      NOT NULL DEFAULT 0,
    gmt_created       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted       DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_revision_batch_id (id),
    KEY idx_revision_target (tenant_id, target_people_id, biz_type, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段修订批次';

CREATE TABLE IF NOT EXISTS field_revision_item (
    pk_id          BIGINT       NOT NULL AUTO_INCREMENT,
    id             VARCHAR(32)  NOT NULL,
    batch_id       VARCHAR(32)  NOT NULL,
    field_path     VARCHAR(128) NOT NULL,
    old_value      JSON         NULL,
    new_value      JSON         NULL,
    old_display    VARCHAR(512) NULL,
    new_display    VARCHAR(512) NULL,
    is_deleted     TINYINT      NOT NULL DEFAULT 0,
    gmt_created    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted    DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_revision_item_id (id),
    KEY idx_revision_item_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段修订明细';

CREATE TABLE IF NOT EXISTS care_team (
    pk_id                           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    team_code                       VARCHAR(32)  NOT NULL COMMENT '健管组编码（02 开头 32 位）',
    name                            VARCHAR(128) NOT NULL COMMENT '健管组名称',
    primary_care_manager_staff_id VARCHAR(32)  NOT NULL COMMENT '主责健管师业务ID',
    primary_doctor_staff_id VARCHAR(32)  NULL COMMENT '主责医生业务ID',
    status                          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    is_deleted                      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created                     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified                    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted                     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_care_team_id (id),
    UNIQUE KEY uk_care_team_code (team_code, gmt_deleted),
    KEY idx_care_team_org (org_id, is_deleted),
    KEY idx_care_team_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管组（机构网格）';

CREATE TABLE IF NOT EXISTS care_team_member (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    team_id             VARCHAR(32)  NOT NULL COMMENT '健管组业务ID',
    member_type     VARCHAR(16)  NOT NULL COMMENT '成员类型：STAFF/PATIENT',
    staff_id            VARCHAR(32)  NULL COMMENT '员工业务ID',
    people_id           VARCHAR(32)  NULL COMMENT '患者业务ID',
    joined_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_care_team_member_id (id),
    KEY idx_care_member_team (team_id, is_deleted),
    KEY idx_care_member_staff (team_id, staff_id),
    KEY idx_care_member_people_org (org_id, people_id, is_deleted),
    KEY idx_care_member_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管组成员';

-- =============================================================================
-- P1: 体征 / Agent / 审计
-- =============================================================================

CREATE TABLE IF NOT EXISTS vital_record (
    pk_id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    org_id              VARCHAR(32)  NULL COMMENT '录入时工作机构业务ID',
    metric_type     VARCHAR(32)    NOT NULL COMMENT '指标类型',
    value           DECIMAL(12, 4) NOT NULL COMMENT '指标数值',
    unit            VARCHAR(16)    NULL COMMENT '单位',
    recorded_at     DATETIME       NOT NULL COMMENT '测量/记录时间',
    source          VARCHAR(32)    NOT NULL DEFAULT 'SELF' COMMENT '来源：SELF/DEVICE/STAFF',
    group_id        VARCHAR(32)    NULL COMMENT '同一次测量分组ID（血压成对等）',
    note            VARCHAR(200)   NULL COMMENT '备注',
    recorded_by_staff_id VARCHAR(32) NULL COMMENT 'B端代录人员工业务ID',
    extra_json      JSON           NULL COMMENT '扩展信息 JSON（bpContext/mealContext等）',
    is_deleted      TINYINT        NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME       NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_vital_record_id (id),
    KEY idx_vital_tenant_people_metric_time (tenant_id, people_id, metric_type, recorded_at),
    KEY idx_vital_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标/体征记录（租户内共享）';

-- =============================================================================
-- 检验报告（患者级，B 端手工录入）
-- =============================================================================

CREATE TABLE IF NOT EXISTS lab_report (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    org_id              VARCHAR(32)  NULL COMMENT '录入时工作机构业务ID',
    specimen_type       VARCHAR(32)  NOT NULL DEFAULT 'BLOOD' COMMENT '标本：BLOOD/URINE',
    sampled_at          DATETIME     NULL COMMENT '采样时间',
    reported_at         DATETIME     NULL COMMENT '报告时间',
    source              VARCHAR(32)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源：STAFF/STAFF_OCR/PATIENT/PATIENT_OCR（兼容 MANUAL/OCR）',
    note                VARCHAR(200) NULL COMMENT '备注',
    created_by_staff_id VARCHAR(32)  NULL COMMENT '创建人员工业务ID',
    updated_by_staff_id VARCHAR(32)  NULL COMMENT '更新人员工业务ID',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_lab_report_id (id),
    KEY idx_lab_report_people (tenant_id, people_id, reported_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检验报告头';

CREATE TABLE IF NOT EXISTS lab_result_item (
    pk_id           BIGINT         NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)    NOT NULL COMMENT '业务主键（雪花）',
    report_id       VARCHAR(32)    NOT NULL COMMENT '检验报告业务ID',
    item_code       VARCHAR(32)    NOT NULL COMMENT '项目编码',
    item_name       VARCHAR(64)    NOT NULL COMMENT '项目名称',
    value_num       DECIMAL(16, 4) NULL COMMENT '数值结果',
    value_text      VARCHAR(64)    NULL COMMENT '定性/文本结果',
    unit            VARCHAR(32)    NULL COMMENT '单位',
    ref_low         DECIMAL(16, 4) NULL COMMENT '参考下限',
    ref_high        DECIMAL(16, 4) NULL COMMENT '参考上限',
    abnormal_flag   VARCHAR(8)     NULL COMMENT '异常标记：H/L/N',
    is_deleted      TINYINT        NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME       NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_lab_result_item_id (id),
    KEY idx_lab_item_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检验明细项';

-- =============================================================================
-- 检查报告（患者级；B 端代录 / C 端自行上报）
-- =============================================================================

CREATE TABLE IF NOT EXISTS exam_report (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    org_id              VARCHAR(32)  NULL COMMENT '录入时工作机构业务ID',
    exam_type           VARCHAR(32)  NOT NULL COMMENT '检查类型：ECG/UCG/CAROTID_US/FUNDUS/PFT/CHEST_IMAGING',
    examined_at         DATETIME     NOT NULL COMMENT '检查时间',
    conclusion          VARCHAR(1000) NULL COMMENT '结论摘要',
    findings_json       JSON         NULL COMMENT '结构化关键测量',
    source              VARCHAR(32)  NOT NULL DEFAULT 'MANUAL' COMMENT '来源：STAFF/PATIENT（兼容 MANUAL）',
    created_by_staff_id VARCHAR(32)  NULL COMMENT '创建人员工业务ID',
    updated_by_staff_id VARCHAR(32)  NULL COMMENT '更新人员工业务ID',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_exam_report_id (id),
    KEY idx_exam_report_people (tenant_id, people_id, examined_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='检查报告';

CREATE TABLE IF NOT EXISTS agent_session (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    agent_type      VARCHAR(32)  NOT NULL COMMENT 'Agent 类型：PATIENT/CARE_COPILOT',
    tenant_id           VARCHAR(32)  NULL COMMENT '租户业务ID',
    people_id           VARCHAR(32)  NULL COMMENT '患者业务ID',
    staff_id            VARCHAR(32)  NULL COMMENT '员工业务ID',
    org_id              VARCHAR(32)  NULL COMMENT '工作机构业务ID',
    title           VARCHAR(128) NULL COMMENT '会话标题',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/CLOSED',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_agent_session_id (id),
    KEY idx_agent_session_people (people_id, agent_type),
    KEY idx_agent_session_tenant (tenant_id, agent_type),
    KEY idx_agent_session_staff (staff_id, agent_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 会话';

CREATE TABLE IF NOT EXISTS agent_message (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    session_id          VARCHAR(32)  NOT NULL COMMENT '会话业务ID',
    role            VARCHAR(16)  NOT NULL COMMENT '角色：USER/ASSISTANT/SYSTEM/TOOL',
    content         LONGTEXT     NOT NULL COMMENT '消息内容',
    tool_name       VARCHAR(64)  NULL COMMENT '工具名称',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_agent_message_id (id),
    KEY idx_agent_msg_session (session_id, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 会话消息';

CREATE TABLE IF NOT EXISTS agent_interaction_log (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    session_id          VARCHAR(32)  NULL COMMENT '会话业务ID',
    agent_type          VARCHAR(32)  NOT NULL COMMENT 'Agent 类型',
    tenant_id           VARCHAR(32)  NULL COMMENT '租户业务ID',
    people_id           VARCHAR(32)  NULL COMMENT '患者业务ID',
    staff_id            VARCHAR(32)  NULL COMMENT '员工业务ID',
    intent              VARCHAR(64)  NULL COMMENT '识别意图',
    user_message        LONGTEXT     NOT NULL COMMENT '用户原话',
    prompt_snapshot     LONGTEXT     NULL COMMENT 'Prompt 快照',
    tool_calls_json     LONGTEXT     NULL COMMENT '工具调用 JSON',
    draft_reply         LONGTEXT     NULL COMMENT '草稿回复',
    final_reply         LONGTEXT     NULL COMMENT '最终回复',
    prompt_tokens       INT          NULL COMMENT 'Prompt Token 数',
    completion_tokens   INT          NULL COMMENT '补全 Token 数',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_agent_interaction_log_id (id),
    KEY idx_agent_log_people_time (people_id, gmt_created),
    KEY idx_agent_log_tenant_time (tenant_id, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 交互日志';

CREATE TABLE IF NOT EXISTS audit_log (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    portal              VARCHAR(8)   NOT NULL COMMENT '入口：C/B/OPS',
    actor_account_id    VARCHAR(32)  NOT NULL COMMENT '操作者账号业务ID',
    actor_type          VARCHAR(16)  NOT NULL COMMENT '操作者类型：OPS/STAFF/PATIENT',
    tenant_id           VARCHAR(32)  NULL COMMENT '相关租户业务ID',
    action              VARCHAR(64)  NOT NULL COMMENT '动作编码',
    resource_type       VARCHAR(64)  NULL COMMENT '资源类型',
    resource_id         VARCHAR(64)  NULL COMMENT '资源业务ID',
    people_id           VARCHAR(32)  NULL COMMENT '相关患者业务ID',
    detail_json         JSON         NULL COMMENT '详情 JSON',
    ip                  VARCHAR(64)  NULL COMMENT '客户端 IP',
    user_agent          VARCHAR(256) NULL COMMENT 'User-Agent',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_audit_log_id (id),
    KEY idx_audit_action_time (action, gmt_created),
    KEY idx_audit_people_time (people_id, gmt_created),
    KEY idx_audit_actor_time (actor_type, actor_account_id, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统审计日志';

-- =============================================================================
-- 管理报告 + 其它预留表
-- =============================================================================

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理报告（周/月/季）';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息中心逻辑消息/站内信';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息通道投递';

CREATE TABLE IF NOT EXISTS followup_schedule_rule (
    pk_id BIGINT NOT NULL AUTO_INCREMENT,
    id VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(32) NOT NULL,
    org_id VARCHAR(32) NOT NULL DEFAULT '' COMMENT '空串=全租户',
    disease_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '空串=不限病种',
    interval_with_plan INT NOT NULL COMMENT '有执行中方案的间隔天数',
    interval_no_plan INT NOT NULL COMMENT '无方案的间隔天数',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0=不自动排期',
    updated_by_staff_id VARCHAR(32) NULL,
    is_deleted TINYINT NOT NULL DEFAULT 0,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted DATETIME NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_followup_rule_id (id),
    UNIQUE KEY uk_followup_rule_scope (tenant_id, org_id, disease_code, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定期随访排期规则';

CREATE TABLE IF NOT EXISTS web_push_subscription (
    pk_id BIGINT NOT NULL AUTO_INCREMENT,
    id VARCHAR(32) NOT NULL,
    account_id VARCHAR(32) NOT NULL COMMENT 'C 端账号',
    endpoint VARCHAR(1024) NOT NULL,
    endpoint_hash CHAR(64) NOT NULL COMMENT 'endpoint SHA-256',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='浏览器推送订阅';

CREATE TABLE IF NOT EXISTS web_push_vapid (
    pk_id BIGINT NOT NULL AUTO_INCREMENT,
    id VARCHAR(32) NOT NULL,
    public_key VARCHAR(128) NOT NULL,
    private_key VARCHAR(128) NOT NULL,
    gmt_created DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_web_push_vapid_id (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Web Push VAPID 密钥（未配置时自动生成）';

CREATE TABLE IF NOT EXISTS tenant_quota_usage (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    quota_key       VARCHAR(64)  NOT NULL COMMENT '配额项',
    used_value      BIGINT       NOT NULL DEFAULT 0 COMMENT '已用量',
    limit_value     BIGINT       NULL COMMENT '上限',
    period_key      VARCHAR(32)  NOT NULL DEFAULT 'TOTAL' COMMENT '周期键',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_tenant_quota_usage_id (id),
    UNIQUE KEY uk_quota (tenant_id, quota_key, period_key, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户配额用量（P2）';

CREATE TABLE IF NOT EXISTS feature_flag (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '租户业务ID；空串表示平台级（唯一键不容 NULL 去重）',
    flag_key        VARCHAR(64)  NOT NULL COMMENT '开关键',
    enabled         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否开启',
    config_json     JSON         NULL COMMENT '扩展配置 JSON',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_feature_flag_id (id),
    UNIQUE KEY uk_flag (tenant_id, flag_key, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='功能开关（P2）';

CREATE TABLE IF NOT EXISTS tenant_config (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    app_name        VARCHAR(64)  NULL COMMENT '对外应用名',
    logo_url        VARCHAR(512) NULL COMMENT 'Logo 地址',
    primary_color   VARCHAR(16)  NULL COMMENT '主色，形如 #2b9e9e',
    login_slogan    VARCHAR(128) NULL COMMENT '登录页副标题',
    support_phone   VARCHAR(32)  NULL COMMENT '客服电话',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_tenant_config_id (id),
    UNIQUE KEY uk_tenant_config_tenant (tenant_id, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户白标配置';

-- =============================================================================
-- 平台定时任务
-- =============================================================================

CREATE TABLE IF NOT EXISTS sys_job_def (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    job_code        VARCHAR(64)  NOT NULL COMMENT '任务编码，与 Handler 绑定',
    display_name    VARCHAR(128) NOT NULL COMMENT '展示名',
    cron_expr       VARCHAR(64)  NOT NULL COMMENT 'Spring 6 位 Cron',
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '时区',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0否 1是',
    params_json     TEXT         NULL COMMENT '默认参数 JSON（P0 不开放 UI 编辑）',
    next_fire_at    DATETIME     NULL COMMENT '下次计划执行时间',
    last_fire_at    DATETIME     NULL COMMENT '最近开始时间',
    last_status     VARCHAR(32)  NULL COMMENT '最近状态',
    last_message    VARCHAR(512) NULL COMMENT '最近摘要',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_job_def_id (id),
    UNIQUE KEY uk_job_def_code (job_code, gmt_deleted),
    KEY idx_job_def_due (enabled, next_fire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台定时任务配置';

CREATE TABLE IF NOT EXISTS sys_job_run (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    job_def_id      VARCHAR(32)  NOT NULL COMMENT '任务配置业务ID',
    job_code        VARCHAR(64)  NOT NULL COMMENT '任务编码',
    trigger_type    VARCHAR(16)  NOT NULL COMMENT 'SCHEDULE/MANUAL',
    status          VARCHAR(16)  NOT NULL COMMENT 'RUNNING/SUCCESS/FAILED/SKIPPED',
    started_at      DATETIME     NOT NULL COMMENT '开始时间',
    finished_at     DATETIME     NULL COMMENT '结束时间',
    message         VARCHAR(512) NULL COMMENT '摘要',
    detail_json     TEXT         NULL COMMENT '汇总指标 JSON，不含 PHI',
    instance_id     VARCHAR(128) NULL COMMENT '执行节点标识',
    triggered_by    VARCHAR(32)  NULL COMMENT '手动触发 Ops 账号 ID',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_job_run_id (id),
    KEY idx_job_run_code_time (job_code, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台定时任务执行日志';

-- =============================================================================
-- 用药管理（患者级，B 端维护；租户共享）
-- =============================================================================

CREATE TABLE IF NOT EXISTS people_medication (
    pk_id                   BIGINT         NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)    NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)    NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)    NOT NULL COMMENT '患者业务ID',
    org_id                  VARCHAR(32)    NULL COMMENT '录入时工作机构业务ID',
    prescription_group_id   VARCHAR(32)    NULL COMMENT '同一次处方分组ID；手工录入可空',
    source                  VARCHAR(32)    NOT NULL DEFAULT 'MANUAL' COMMENT '来源：MANUAL/PRESCRIPTION',
    drug_name               VARCHAR(128)   NOT NULL COMMENT '药品名称',
    usage_method            VARCHAR(64)    NOT NULL COMMENT '药品用法，如口服',
    frequency               VARCHAR(128)   NULL COMMENT '用药频率',
    dose_amount             VARCHAR(32)    NULL COMMENT '单次剂量数值',
    dose_unit               VARCHAR(32)    NULL COMMENT '单次剂量单位',
    start_date              DATE           NULL COMMENT '开始服药时间',
    stop_date               DATE           NULL COMMENT '停药时间',
    timing_note             VARCHAR(128)   NULL COMMENT '用药时机：晨起/饭后/痛时服等',
    course_days             INT            NULL COMMENT '疗程天数；空且无停药日可表示长期',
    time_morning            VARCHAR(32)    NULL COMMENT '用药时间-早（兼容旧字段，新录入不再使用）',
    time_noon               VARCHAR(32)    NULL COMMENT '用药时间-午（兼容旧字段，新录入不再使用）',
    time_evening            VARCHAR(32)    NULL COMMENT '用药时间-晚（兼容旧字段，新录入不再使用）',
    time_bedtime            VARCHAR(32)    NULL COMMENT '用药时间-睡前（兼容旧字段，新录入不再使用）',
    has_adverse_reaction    TINYINT        NOT NULL DEFAULT 0 COMMENT '是否存在不良反应：0否 1是',
    remark                  VARCHAR(150)   NULL COMMENT '备注',
    status                  VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE在用/STOPPED停用',
    drug_code               VARCHAR(64)    NULL COMMENT '药品目录编码',
    slots_json              VARCHAR(128)   NULL COMMENT '服药时段覆盖 ["MORNING","BEDTIME"]；空=按频次+时机推导',
    verified_at             DATETIME       NULL COMMENT '患者自添加药的医护核对时间',
    verified_by_staff_id    VARCHAR(32)    NULL COMMENT '核对人员工业务ID',
    allergy_conflict        VARCHAR(255)   NULL COMMENT '保存时命中的药物过敏摘要；空=无',
    stop_reason             VARCHAR(32)    NULL COMMENT '患者自述停药原因 IMPROVED/SIDE_EFFECT/DOCTOR/OTHER',
    created_by_staff_id     VARCHAR(32)    NULL COMMENT '创建人员工业务ID',
    updated_by_staff_id     VARCHAR(32)    NULL COMMENT '最后更新人员工业务ID',
    is_deleted              TINYINT        NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME       NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_medication_id (id),
    KEY idx_med_tenant_people (tenant_id, people_id, status),
    KEY idx_med_prescription_group (prescription_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者用药清单（患者级）';

CREATE TABLE IF NOT EXISTS people_medication_intake (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    medication_id       VARCHAR(32)  NOT NULL COMMENT '用药清单业务ID',
    intake_date         DATE         NOT NULL COMMENT '服药日期',
    time_slot           VARCHAR(16)  NOT NULL COMMENT '时段：MORNING/NOON/EVENING/BEDTIME/PRN（OTHER 仅历史）',
    dose_seq            TINYINT      NOT NULL DEFAULT 1 COMMENT 'PRN 同日第几次；定时药恒为 1',
    status              VARCHAR(16)  NOT NULL COMMENT '状态：TAKEN已服/MISSED漏服/SKIPPED跳过',
    taken_at            DATETIME     NULL COMMENT '实际服药时刻',
    backfill            TINYINT      NOT NULL DEFAULT 0 COMMENT '1=次日补记',
    note                VARCHAR(200) NULL COMMENT '备注',
    recorded_by_staff_id VARCHAR(32) NULL COMMENT '记录人员工业务ID',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_medication_intake_id (id),
    UNIQUE KEY uk_med_intake_day_slot_seq (medication_id, intake_date, time_slot, dose_seq, gmt_deleted),
    KEY idx_med_intake_people_date (tenant_id, people_id, intake_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用药依从性打卡记录';

CREATE TABLE IF NOT EXISTS people_medication_reminder (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id       VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '提醒开关',
    morning_at      CHAR(5)      NOT NULL DEFAULT '08:00' COMMENT '早上提醒时间',
    noon_at         CHAR(5)      NOT NULL DEFAULT '12:00' COMMENT '中午提醒时间',
    evening_at      CHAR(5)      NOT NULL DEFAULT '18:00' COMMENT '晚上提醒时间',
    bedtime_at      CHAR(5)      NOT NULL DEFAULT '21:30' COMMENT '睡前提醒时间',
    follow_up_min   INT          NOT NULL DEFAULT 30 COMMENT '未打卡跟进提醒间隔（分钟），0=不跟进',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_med_reminder_id (id),
    UNIQUE KEY uk_med_reminder_people (tenant_id, people_id, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服药提醒设置；无行=默认开启+默认时间';

CREATE TABLE IF NOT EXISTS people_medication_reminder_log (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    tenant_id       VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    remind_date     DATE         NOT NULL COMMENT '提醒日期',
    time_slot       VARCHAR(16)  NOT NULL COMMENT '时段',
    seq             TINYINT      NOT NULL COMMENT '1=到点 2=跟进',
    sent_at         DATETIME     NOT NULL COMMENT '发送时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_med_remind (people_id, remind_date, time_slot, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服药提醒发送去重';

CREATE TABLE IF NOT EXISTS people_medication_feedback (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    medication_id       VARCHAR(32)  NOT NULL COMMENT '用药清单业务ID',
    kind                VARCHAR(24)  NOT NULL COMMENT 'ADVERSE 不适 / ADJUST 申请调整 / STOPPED 自述停药 / ALLERGY_CONFLICT 过敏冲突',
    symptoms_json       VARCHAR(255) NULL COMMENT '症状码数组',
    severity            VARCHAR(16)  NULL COMMENT 'MILD/MODERATE/SEVERE',
    content             VARCHAR(500) NULL COMMENT '描述',
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/HANDLED',
    work_task_id        VARCHAR(32)  NULL COMMENT '关联工作台任务',
    handled_by_staff_id VARCHAR(32)  NULL COMMENT '处理人',
    handled_at          DATETIME     NULL COMMENT '处理时间',
    reply               VARCHAR(500) NULL COMMENT '健管师回复',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_med_feedback_id (id),
    KEY idx_med_feedback_people (tenant_id, people_id, status),
    KEY idx_med_feedback_med (medication_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者用药反馈';

-- =============================================================================
-- 健康管理方案（Care Plan）
-- =============================================================================

CREATE TABLE IF NOT EXISTS care_plan (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/SUPERSEDED/ARCHIVED',
    title                   VARCHAR(128) NULL COMMENT '方案标题',
    goal_summary            VARCHAR(512) NULL COMMENT '一句话目标',
    disease_tags_json       JSON         NULL COMMENT '关联病种 code 列表',
    current_version_id      VARCHAR(32)  NULL COMMENT '当前生效版本业务ID',
    version                 INT          NOT NULL DEFAULT 1 COMMENT '头乐观锁',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康管理方案头（租户+患者）';

CREATE TABLE IF NOT EXISTS care_plan_draft (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
    id                      VARCHAR(32)  NOT NULL,
    plan_id                 VARCHAR(32)  NOT NULL,
    tenant_id               VARCHAR(32)  NOT NULL,
    people_id               VARCHAR(32)  NOT NULL,
    version                 INT          NOT NULL DEFAULT 1 COMMENT '草稿乐观锁',
    schema_version          INT          NOT NULL DEFAULT 1 COMMENT '产品规则版本（CarePlanSchemaVersions）',
    source                  VARCHAR(32)  NOT NULL DEFAULT 'MANUAL' COMMENT 'TEMPLATE/MANUAL/TEMPLATE_THEN_EDIT/LLM/...',
    exercise_json           JSON         NULL,
    diet_json               JSON         NULL,
    execution_json          JSON         NULL,
    safety_flags_json       JSON         NULL,
    context_snapshot_json   JSON         NULL,
    base_version_id         VARCHAR(32)  NULL COMMENT '从哪一版 clone',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康管理方案草稿工作区';

CREATE TABLE IF NOT EXISTS care_plan_version (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
    id                      VARCHAR(32)  NOT NULL,
    plan_id                 VARCHAR(32)  NOT NULL,
    version_no              INT          NOT NULL COMMENT '同患者发布序号（递增，非规则版本）',
    schema_version          INT          NOT NULL DEFAULT 1 COMMENT '产品规则版本（CarePlanSchemaVersions）',
    source                  VARCHAR(32)  NOT NULL,
    title                   VARCHAR(128) NULL COMMENT '发布时标题快照',
    exercise_json           JSON         NULL,
    diet_json               JSON         NULL,
    execution_json          JSON         NULL,
    context_snapshot_json   JSON         NULL,
    safety_flags_json       JSON         NULL,
    published_at            DATETIME     NOT NULL,
    published_by_staff_id   VARCHAR(32)  NOT NULL,
    signed_by_staff_id      VARCHAR(32)  NULL,
    signed_at               DATETIME     NULL,
    sign_status             VARCHAR(16)  NOT NULL DEFAULT 'UNSIGNED' COMMENT 'UNSIGNED/SIGNED',
    is_deleted              TINYINT      NOT NULL DEFAULT 0,
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_care_plan_version_id (id),
    UNIQUE KEY uk_care_plan_version_no (plan_id, version_no, gmt_deleted),
    KEY idx_care_plan_version_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康管理方案已发布版本（不可变）';

CREATE TABLE IF NOT EXISTS care_plan_task (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
    id                      VARCHAR(32)  NOT NULL,
    tenant_id               VARCHAR(32)  NOT NULL,
    people_id               VARCHAR(32)  NOT NULL,
    plan_id                 VARCHAR(32)  NOT NULL,
    plan_version_id         VARCHAR(32)  NOT NULL,
    task_code               VARCHAR(64)  NOT NULL,
    title                   VARCHAR(128) NOT NULL,
    category                VARCHAR(32)  NOT NULL COMMENT 'EXERCISE/DIET/OTHER',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康管理方案执行任务投影（发布时写入）';

CREATE TABLE IF NOT EXISTS care_plan_task_checkin (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT,
    id                      VARCHAR(32)  NOT NULL,
    tenant_id               VARCHAR(32)  NOT NULL,
    people_id               VARCHAR(32)  NOT NULL,
    plan_id                 VARCHAR(32)  NOT NULL,
    plan_version_id         VARCHAR(32)  NOT NULL,
    task_id                 VARCHAR(32)  NOT NULL,
    checkin_date            DATE         NOT NULL COMMENT '打卡日期',
    time_slot               VARCHAR(32)  NOT NULL DEFAULT 'ALL' COMMENT '时段（与任务一致或 ALL）',
    status                  VARCHAR(16)  NOT NULL COMMENT 'DONE/SKIPPED/MISSED',
    note                    VARCHAR(200) NULL,
    recorded_by_people_id   VARCHAR(32)  NULL COMMENT 'C 端患者自录',
    recorded_by_staff_id    VARCHAR(32)  NULL COMMENT 'B 端员工代录',
    is_deleted              TINYINT      NOT NULL DEFAULT 0,
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_care_plan_task_checkin_id (id),
    UNIQUE KEY uk_care_plan_task_checkin_slot (task_id, checkin_date, time_slot, gmt_deleted),
    KEY idx_care_plan_task_checkin_people_date (tenant_id, people_id, checkin_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理方案执行任务打卡记录';

-- =============================================================================
-- 依从性日快照（看板趋势）
-- =============================================================================

CREATE TABLE IF NOT EXISTS adherence_daily_snapshot (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NOT NULL COMMENT '机构业务ID',
    care_team_id            VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '健管组业务ID；空串=机构整体',
    snapshot_date           DATE         NOT NULL COMMENT '快照日期',
    universe_count          INT          NOT NULL DEFAULT 0 COMMENT '纳入统计的在管患者数',
    follow_up_count         INT          NOT NULL DEFAULT 0 COMMENT '需跟进（高风险）人数',
    plan_incomplete_count   INT          NOT NULL DEFAULT 0 COMMENT '方案未完成人数',
    med_incomplete_count    INT          NOT NULL DEFAULT 0 COMMENT '用药未完成人数',
    streak_ge3_count        INT          NOT NULL DEFAULT 0 COMMENT '连续未打卡达阈值人数',
    plan_due_sum            INT          NOT NULL DEFAULT 0 COMMENT '方案应打次数合计',
    plan_done_sum           INT          NOT NULL DEFAULT 0 COMMENT '方案已打次数合计',
    med_due_dose_sum        INT          NOT NULL DEFAULT 0 COMMENT '用药应服次数合计（按频次展开）',
    med_taken_dose_sum      INT          NOT NULL DEFAULT 0 COMMENT '用药已服次数合计',
    plan_rate               DECIMAL(6,4) NULL COMMENT '方案完成率 0-1',
    med_rate                DECIMAL(6,4) NULL COMMENT '用药按次达标率 0-1',
    diet_logged_count       INT          NOT NULL DEFAULT 0 COMMENT '当日有饮食记录人数',
    exercise_logged_count   INT          NOT NULL DEFAULT 0 COMMENT '当日有运动记录人数',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_adherence_snapshot_id (id),
    UNIQUE KEY uk_adherence_snapshot_scope (org_id, care_team_id, snapshot_date),
    KEY idx_adherence_snapshot_tenant (tenant_id, snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='依从性日快照（机构/健管组）';

-- =============================================================================
-- 工作台任务（机构 inbox）
-- =============================================================================

CREATE TABLE IF NOT EXISTS workspace_task (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    task_type               VARCHAR(32)  NOT NULL COMMENT 'TEAM_ASSIGN/PLAN_CREATE/PLAN_NUDGE/METRIC_ALERT/FOLLOW_UP/REPORT_REVIEW',
    biz_key                 VARCHAR(128) NOT NULL COMMENT '去重业务键',
    open_dedup_key          VARCHAR(160) GENERATED ALWAYS AS (
        CASE
            WHEN is_deleted = 0 AND status = 'OPEN' THEN CONCAT(task_type, ':', biz_key)
            ELSE id
        END
    ) STORED COMMENT 'OPEN 时 type:biz_key，否则 id，保证同时仅一条 OPEN',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DONE/CANCELLED/EXPIRED',
    priority                VARCHAR(16)  NOT NULL DEFAULT 'HIGH' COMMENT 'HIGH/MEDIUM/LOW',
    assignee_staff_id       VARCHAR(32)  NULL COMMENT '处理人 staff_profile.id；空=公共池',
    title                   VARCHAR(128) NOT NULL COMMENT '标题',
    summary                 VARCHAR(512) NULL COMMENT '列表摘要',
    payload_json            JSON         NULL COMMENT '类型特有快照',
    source                  VARCHAR(16)  NOT NULL DEFAULT 'JOB' COMMENT 'EVENT/JOB/MANUAL',
    close_reason            VARCHAR(16)  NULL COMMENT 'CONDITION/FORM/EXPIRED/CANCEL',
    due_at                  DATETIME     NULL COMMENT '截止时间',
    opened_at               DATETIME     NOT NULL COMMENT '开单时间',
    done_at                 DATETIME     NULL COMMENT '关单时间',
    done_by_staff_id        VARCHAR(32)  NULL COMMENT '关单人；条件自闭可空',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_workspace_task_id (id),
    UNIQUE KEY uk_workspace_task_open (tenant_id, org_id, open_dedup_key, gmt_deleted),
    KEY idx_workspace_task_org_assignee (org_id, status, assignee_staff_id),
    KEY idx_workspace_task_org_people (org_id, people_id, status),
    KEY idx_workspace_task_org_type (org_id, task_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构工作台待办（公共池/个人池）';

CREATE TABLE IF NOT EXISTS followup_record (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    workspace_task_id       VARCHAR(32)  NULL COMMENT '绑定的 workspace_task.id；可空',
    record_type             VARCHAR(32)  NOT NULL COMMENT 'PLAN_NUDGE/METRIC_REVIEW/PERIODIC',
    source                  VARCHAR(32)  NOT NULL COMMENT 'MANUAL/WORKSPACE_TASK/ESCALATE/SCHEDULE_JOB/PATIENT_REQUEST',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/DONE/CANCELLED',
    title                   VARCHAR(128) NOT NULL COMMENT '标题',
    summary                 VARCHAR(512) NULL COMMENT '列表摘要',
    planned_at              DATETIME     NULL COMMENT '计划随访时间',
    due_at                  DATETIME     NULL COMMENT '办理时限',
    assignee_staff_id       VARCHAR(32)  NULL COMMENT '归属人 staff_profile.id',
    contact_channel         VARCHAR(32)  NULL COMMENT 'PHONE/WECHAT/ONSITE/OTHER',
    contact_result          VARCHAR(32)  NULL COMMENT 'REACHED/UNREACHED/PARTIAL',
    content_json            JSON         NULL COMMENT '结构化随访内容',
    cancel_reason           VARCHAR(512) NULL COMMENT '取消原因',
    completed_at            DATETIME     NULL COMMENT '完成时间',
    completed_by_staff_id   VARCHAR(32)  NULL COMMENT '完成人 staff_profile.id',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_followup_record_id (id),
    KEY idx_followup_org_people (org_id, people_id, status),
    KEY idx_followup_org_status (org_id, status, due_at),
    KEY idx_followup_task (workspace_task_id),
    KEY idx_followup_assignee (assignee_staff_id, status),
    KEY idx_followup_type (org_id, record_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='随访记录（打卡跟进/指标异常处理/定期随访）';

-- =============================================================================
-- 患病风险评估快照（跟人；无机构字段）
-- =============================================================================

CREATE TABLE IF NOT EXISTS people_assessment_snapshot (
    pk_id                   BIGINT         NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)    NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)    NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)    NOT NULL COMMENT '患者业务ID',
    kind                    VARCHAR(32)    NOT NULL COMMENT 'RISK 患病风险 / SEVERITY 严重等级分层',
    engine_code             VARCHAR(64)    NOT NULL COMMENT 'CDRS/HYPERTENSION_RISK/OBESITY_RISK/DIABETES_SEVERITY/...',
    disease_code            VARCHAR(64)    NULL COMMENT '关联病种，可空',
    rule_pack_version       VARCHAR(64)    NOT NULL COMMENT '规则包版本',
    status                  VARCHAR(16)    NOT NULL COMMENT 'COMPLETE/INCOMPLETE',
    level                   VARCHAR(32)    NULL COMMENT '引擎原生等级',
    tier                    VARCHAR(16)    NULL COMMENT '统一等级 LOW/MID/HIGH/VERY_HIGH',
    score                   DECIMAL(10,2)  NULL COMMENT '总分，可空',
    probability             DECIMAL(8,4)   NULL COMMENT '概率，可空',
    result_json             JSON           NULL COMMENT '分项+advice+guideline',
    input_snapshot_json     JSON           NULL COMMENT '计算输入快照（仅引擎声明字段）',
    fingerprint             VARCHAR(64)    NULL COMMENT '结果指纹，未变化不新增快照',
    trigger_source          VARCHAR(16)    NOT NULL COMMENT 'MANUAL/AUTO/QUESTIONNAIRE/JOB(旧)',
    assessed_at             DATETIME       NOT NULL COMMENT '评估时间',
    assessed_by_staff_id    VARCHAR(32)    NULL COMMENT '人工评估人；Job 可空',
    is_deleted              TINYINT        NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME       NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_assessment_snapshot_id (id),
    KEY idx_assessment_people_engine (tenant_id, people_id, engine_code, assessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患病风险/严重度评估快照';

CREATE TABLE IF NOT EXISTS people_assessment_latest (
    pk_id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    tenant_id       VARCHAR(32)   NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)   NOT NULL COMMENT '患者业务ID',
    engine_code     VARCHAR(64)   NOT NULL COMMENT '引擎编码',
    category        VARCHAR(16)   NOT NULL COMMENT 'RISK/SEVERITY',
    disease_code    VARCHAR(32)   NOT NULL COMMENT 'diabetes/hypertension/obesity',
    snapshot_id     VARCHAR(32)   NOT NULL COMMENT '最新快照业务ID',
    status          VARCHAR(16)   NOT NULL COMMENT 'COMPLETE/INCOMPLETE',
    level           VARCHAR(32)   NULL COMMENT '引擎原生等级',
    level_label     VARCHAR(64)   NULL COMMENT '等级名称',
    tier            VARCHAR(16)   NULL COMMENT '统一等级',
    score           DECIMAL(10,2) NULL COMMENT '分数（仅评分类引擎）',
    active          TINYINT       NOT NULL DEFAULT 1 COMMENT '当前是否适用：确诊后风险引擎置 0',
    fingerprint     VARCHAR(64)   NULL COMMENT '结果指纹',
    assessed_at     DATETIME      NOT NULL COMMENT '结果产生时间',
    evaluated_at    DATETIME      NOT NULL COMMENT '最近一次计算时间',
    gmt_created     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_assessment_latest (people_id, engine_code),
    KEY idx_assessment_latest_tenant (tenant_id, people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每人每引擎最新评估结果';

CREATE TABLE IF NOT EXISTS people_assessment_state (
    pk_id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    tenant_id       VARCHAR(32)   NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)   NOT NULL COMMENT '患者业务ID',
    dirty_at        DATETIME      NULL COMMENT '输入变更待重算时间；空=无需重算',
    evaluated_at    DATETIME      NULL COMMENT '最近重算时间',
    gmt_created     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_assessment_state_people (people_id),
    KEY idx_assessment_state_dirty (dirty_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估待重算标记';

CREATE TABLE IF NOT EXISTS people_assessment_fact (
    pk_id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)   NOT NULL COMMENT '业务主键（雪花）',
    tenant_id       VARCHAR(32)   NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)   NOT NULL COMMENT '患者业务ID',
    field_code      VARCHAR(64)   NOT NULL COMMENT '评估输入字段编码',
    value_json      JSON          NOT NULL COMMENT '{"value": ...}；UNKNOWN 表示不知道',
    source          VARCHAR(32)   NOT NULL COMMENT 'QUESTIONNAIRE_STAFF/QUESTIONNAIRE_PATIENT',
    submission_id   VARCHAR(32)   NULL COMMENT '来源问卷提交',
    recorded_at     DATETIME      NOT NULL COMMENT '记录时间',
    recorded_by     VARCHAR(32)   NULL COMMENT 'staffId / accountId',
    gmt_created     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_assessment_fact_id (id),
    UNIQUE KEY uk_assessment_fact_field (people_id, field_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估问卷补充事实（档案外字段，仅存最新值）';

CREATE TABLE IF NOT EXISTS assessment_questionnaire_submission (
    pk_id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)   NOT NULL COMMENT '业务主键（雪花）',
    tenant_id       VARCHAR(32)   NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)   NOT NULL COMMENT '患者业务ID',
    portal          VARCHAR(8)    NOT NULL COMMENT 'B/C',
    submitted_by    VARCHAR(32)   NULL COMMENT 'staffId / accountId',
    request_id      VARCHAR(32)   NULL COMMENT '健管师发起的问卷请求',
    answers_json    JSON          NOT NULL COMMENT '全部答案',
    overrides_json  JSON          NULL COMMENT '确认覆盖的档案冲突项（新旧值）',
    submitted_at    DATETIME      NOT NULL COMMENT '提交时间',
    gmt_created     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_assessment_submission_id (id),
    KEY idx_assessment_submission_people (tenant_id, people_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估问卷提交记录';

CREATE TABLE IF NOT EXISTS assessment_questionnaire_request (
    pk_id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id              VARCHAR(32)   NOT NULL COMMENT '业务主键（雪花）',
    tenant_id       VARCHAR(32)   NOT NULL COMMENT '所属租户业务ID',
    people_id       VARCHAR(32)   NOT NULL COMMENT '患者业务ID',
    org_id          VARCHAR(32)   NULL COMMENT '发起机构',
    staff_id        VARCHAR(32)   NOT NULL COMMENT '发起健管师',
    fields_json     JSON          NOT NULL COMMENT '请求填写的字段',
    status          VARCHAR(16)   NOT NULL COMMENT 'PENDING/SUBMITTED/EXPIRED/CANCELLED',
    expires_at      DATETIME      NOT NULL COMMENT '失效时间（7 天）',
    reminded_at     DATETIME      NULL COMMENT '48 小时提醒时间',
    submission_id   VARCHAR(32)   NULL COMMENT '提交记录',
    submitted_at    DATETIME      NULL COMMENT '提交时间',
    gmt_created     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_assessment_request_id (id),
    KEY idx_assessment_request_people (people_id, status),
    KEY idx_assessment_request_status (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管师发给患者的评估问卷';

-- =============================================================================
-- 沟通域 CareChat（健管师 ↔ 患者，与 notify 并列）
-- =============================================================================

CREATE TABLE IF NOT EXISTS care_chat_thread (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '就诊人业务ID',
    last_message_at         DATETIME     NULL COMMENT '最近消息时间',
    last_message_preview    VARCHAR(120) NULL COMMENT '最近消息摘要',
    last_sender_type        VARCHAR(16)  NULL COMMENT 'STAFF/PATIENT',
    staff_unread_count      INT          NOT NULL DEFAULT 0 COMMENT '健管侧未读',
    patient_unread_count    INT          NOT NULL DEFAULT 0 COMMENT '患者侧未读',
    closed_at               DATETIME     NULL COMMENT '关闭时间；空=开放',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_care_chat_thread_id (id),
    UNIQUE KEY uk_care_chat_thread_org_people (tenant_id, org_id, people_id, gmt_deleted),
    KEY idx_care_chat_thread_people (tenant_id, people_id, last_message_at),
    KEY idx_care_chat_thread_org_unread (tenant_id, org_id, staff_unread_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管沟通会话线程';

CREATE TABLE IF NOT EXISTS care_chat_message (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    thread_id               VARCHAR(32)  NOT NULL COMMENT '会话线程业务ID',
    sender_type             VARCHAR(16)  NOT NULL COMMENT 'STAFF/PATIENT',
    sender_staff_id         VARCHAR(32)  NULL COMMENT '健管侧 staff_profile.id',
    sender_account_id       VARCHAR(32)  NULL COMMENT '患者侧 people_account.id',
    content_type            VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE',
    content                 VARCHAR(2000) NOT NULL COMMENT '正文',
    client_msg_id           VARCHAR(64)  NULL COMMENT '客户端防重 ID',
    recalled_at             DATETIME     NULL COMMENT '撤回时间（P1）',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_care_chat_message_id (id),
    UNIQUE KEY uk_care_chat_message_client (thread_id, client_msg_id, gmt_deleted),
    KEY idx_care_chat_message_thread (thread_id, gmt_created),
    KEY idx_care_chat_message_staff (tenant_id, sender_staff_id, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管沟通消息气泡';

CREATE TABLE IF NOT EXISTS food_item (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NULL COMMENT 'NULL=平台级',
    code                    VARCHAR(64)  NOT NULL COMMENT '食物编码（成分表 foodCode / S_ 补充 / D_ 菜肴）',
    name                    VARCHAR(128) NOT NULL COMMENT '名称',
    aliases_json            JSON         NULL COMMENT '别名',
    kind                    VARCHAR(16)  NOT NULL COMMENT 'INGREDIENT/DISH/PACKAGED',
    category                VARCHAR(32)  NOT NULL COMMENT '大类',
    sub_category            VARCHAR(64)  NULL COMMENT '原始分类',
    energy_kcal             DECIMAL(8,2) NOT NULL COMMENT '能量 kcal/100g 可食部',
    protein_g               DECIMAL(8,2) NULL COMMENT '蛋白质 g/100g',
    fat_g                   DECIMAL(8,2) NULL COMMENT '脂肪 g/100g',
    carb_g                  DECIMAL(8,2) NULL COMMENT '碳水化合物 g/100g（含膳食纤维）',
    fiber_g                 DECIMAL(8,2) NULL COMMENT '膳食纤维 g/100g',
    sodium_mg               DECIMAL(10,2) NULL COMMENT '钠 mg/100g',
    edible_pct              DECIMAL(5,2) NULL COMMENT '可食部 %',
    gi                      INT          NULL COMMENT '血糖生成指数',
    gi_level                VARCHAR(8)   NULL COMMENT 'LOW/MID/HIGH',
    units_json              JSON         NULL COMMENT '家常单位 [{unit,grams}]',
    tags_json               JSON         NULL COMMENT '标签 HIGH_SALT/PICKLED/SUGARY_DRINK...',
    serving_grams           DECIMAL(8,2) NULL COMMENT '菜肴一份克数',
    is_default              TINYINT      NOT NULL DEFAULT 0 COMMENT '同名代表值',
    data_source             VARCHAR(32)  NOT NULL COMMENT '数据出处',
    precision_level         VARCHAR(8)   NOT NULL COMMENT 'A权威/B自建/C估算',
    needs_review            TINYINT      NOT NULL DEFAULT 0 COMMENT '能量恒等式校验未通过',
    seed_version            VARCHAR(32)  NULL COMMENT '种子版本',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_food_item_id (id),
    UNIQUE KEY uk_food_item_code (code, gmt_deleted),
    KEY idx_food_item_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='食物库';

CREATE TABLE IF NOT EXISTS food_recipe_item (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    dish_code               VARCHAR(64)  NOT NULL COMMENT '菜肴 food_item.code',
    ingredient_code         VARCHAR(64)  NOT NULL COMMENT '食材 food_item.code',
    grams                   DECIMAL(8,2) NOT NULL COMMENT '一份菜的生料克数',
    oil_adjustable          TINYINT      NOT NULL DEFAULT 0 COMMENT '随少油/多油缩放',
    sort_order              INT          NOT NULL DEFAULT 0 COMMENT '排序',
    PRIMARY KEY (pk_id),
    KEY idx_food_recipe_dish (dish_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='家常菜菜谱组成';

CREATE TABLE IF NOT EXISTS diet_goal (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    source                  VARCHAR(16)  NOT NULL COMMENT 'AUTO/STAFF',
    goal_type               VARCHAR(16)  NOT NULL COMMENT 'MAINTAIN/LOSE/GAIN',
    rate_kg_per_week        DECIMAL(4,2) NULL COMMENT '减/增重速度',
    activity_level          VARCHAR(16)  NOT NULL COMMENT 'SEDENTARY/LIGHT/MODERATE/ACTIVE',
    disease_key             VARCHAR(32)  NOT NULL COMMENT 'GENERAL/DIABETES/HYPERTENSION/DIABETES_HYPERTENSION',
    basis_weight_kg         DECIMAL(6,2) NULL COMMENT '计算基准体重',
    basis_height_cm         DECIMAL(6,2) NULL COMMENT '计算基准身高',
    basis_age               INT          NULL COMMENT '计算基准年龄',
    basis_gender            VARCHAR(16)  NULL COMMENT '计算基准性别',
    bmr_kcal                INT          NULL COMMENT '基础代谢',
    tdee_kcal               INT          NULL COMMENT '每日总消耗',
    energy_kcal             INT          NOT NULL COMMENT '目标热量',
    carb_g                  INT          NOT NULL COMMENT '目标碳水',
    protein_g               INT          NOT NULL COMMENT '目标蛋白质',
    fat_g                   INT          NOT NULL COMMENT '目标脂肪',
    fiber_g                 INT          NULL COMMENT '目标膳食纤维',
    sodium_mg               INT          NULL COMMENT '钠上限',
    meal_split_json         JSON         NULL COMMENT '三餐分配比例',
    floor_applied           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否触发热量安全下限',
    set_by_staff_id         VARCHAR(32)  NULL COMMENT '设定员工',
    note                    VARCHAR(200) NULL COMMENT '备注',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_diet_goal_id (id),
    UNIQUE KEY uk_diet_goal_people (tenant_id, people_id, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='饮食热量与营养素目标';

CREATE TABLE IF NOT EXISTS diet_meal (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    meal_date               DATE         NOT NULL COMMENT '日期',
    meal_type               VARCHAR(16)  NOT NULL COMMENT 'BREAKFAST/LUNCH/DINNER/SNACK',
    eaten_at                DATETIME     NULL COMMENT '进餐时间',
    source                  VARCHAR(16)  NOT NULL COMMENT 'MANUAL/PHOTO_AI/TEXT_AI/STAFF',
    energy_kcal             DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '能量快照',
    carb_g                  DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '碳水快照',
    protein_g               DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '蛋白质快照',
    fat_g                   DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '脂肪快照',
    fiber_g                 DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '膳食纤维快照',
    sodium_mg               DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '钠快照',
    estimated_kcal_ratio    DECIMAL(4,3) NOT NULL DEFAULT 0 COMMENT '估算项热量占比',
    quality_level           VARCHAR(8)   NULL COMMENT 'GREEN/YELLOW/RED',
    flags_json              JSON         NULL COMMENT '规则提示',
    note                    VARCHAR(200) NULL COMMENT '备注',
    staff_comment           VARCHAR(500) NULL COMMENT '健管师点评',
    comment_staff_id        VARCHAR(32)  NULL COMMENT '点评员工',
    commented_at            DATETIME     NULL COMMENT '点评时间',
    recorded_by_people_id   VARCHAR(32)  NULL COMMENT '患者自录',
    recorded_by_staff_id    VARCHAR(32)  NULL COMMENT '员工代录',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_diet_meal_id (id),
    KEY idx_diet_meal_people_date (tenant_id, people_id, meal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='饮食记录-餐次';

CREATE TABLE IF NOT EXISTS diet_meal_item (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    meal_id                 VARCHAR(32)  NOT NULL COMMENT '餐次业务ID',
    food_code               VARCHAR(64)  NULL COMMENT 'NULL=未命中食物库（估算）',
    food_name               VARCHAR(128) NOT NULL COMMENT '展示名',
    amount                  DECIMAL(8,2) NOT NULL COMMENT '数量',
    unit                    VARCHAR(16)  NOT NULL COMMENT '单位',
    grams                   DECIMAL(8,2) NOT NULL COMMENT '可食部克数',
    oil_level               VARCHAR(8)   NULL COMMENT 'LOW/NORMAL/HIGH',
    energy_kcal             DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '能量快照',
    carb_g                  DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '碳水快照',
    protein_g               DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '蛋白质快照',
    fat_g                   DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '脂肪快照',
    fiber_g                 DECIMAL(8,2) NOT NULL DEFAULT 0 COMMENT '膳食纤维快照',
    sodium_mg               DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '钠快照',
    precision_level         VARCHAR(8)   NOT NULL COMMENT 'A/B/C',
    confidence              DECIMAL(3,2) NULL COMMENT 'AI 识别置信度',
    grams_confirmed         TINYINT      NOT NULL DEFAULT 1 COMMENT '克数是否经用户确认',
    sort_order              INT          NOT NULL DEFAULT 0 COMMENT '排序',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_diet_meal_item_id (id),
    KEY idx_diet_meal_item_meal (meal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='饮食记录-餐次明细';

CREATE TABLE IF NOT EXISTS exercise_session (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    exercise_date           DATE         NOT NULL COMMENT '运动日期（东八区）',
    started_at              DATETIME     NOT NULL COMMENT '开始时间',
    duration_min            INT          NOT NULL COMMENT '时长（分钟）',
    type_code               VARCHAR(64)  NOT NULL COMMENT '运动类型编码（seed/exercise_types.json）',
    type_name               VARCHAR(64)  NOT NULL COMMENT '类型名称快照',
    category                VARCHAR(16)  NOT NULL COMMENT 'AEROBIC/RESISTANCE/FLEXIBILITY/BALANCE/DAILY',
    intensity               VARCHAR(16)  NOT NULL COMMENT 'LIGHT/MODERATE/VIGOROUS（谈话测试）',
    avg_hr                  INT          NULL COMMENT '平均心率（仅记录）',
    distance_m              INT          NULL COMMENT '距离（米）',
    met_value               DECIMAL(4,1) NOT NULL COMMENT 'MET 快照',
    active_min              INT          NOT NULL DEFAULT 0 COMMENT '活跃分钟快照（中等×1 高强度×2）',
    energy_kcal             DECIMAL(8,1) NOT NULL DEFAULT 0 COMMENT '估算消耗快照',
    weight_estimated        TINYINT      NOT NULL DEFAULT 0 COMMENT '体重缺失按 60kg 估算',
    symptoms_json           JSON         NULL COMMENT '运动后不适症状',
    symptom_level           VARCHAR(8)   NOT NULL DEFAULT 'NONE' COMMENT 'NONE/YELLOW/RED',
    flags_json              JSON         NULL COMMENT '规则提示',
    level                   VARCHAR(8)   NOT NULL DEFAULT 'GREEN' COMMENT 'GREEN/YELLOW/RED',
    alert_task_id           VARCHAR(32)  NULL COMMENT '关联 EXERCISE_ALERT 工作台任务',
    care_plan_task_id       VARCHAR(32)  NULL COMMENT '按方案一键记录时关联任务',
    source                  VARCHAR(16)  NOT NULL COMMENT 'MANUAL/PLAN_QUICK/STAFF',
    device_vendor           VARCHAR(32)  NULL COMMENT '设备来源（预留）',
    external_id             VARCHAR(64)  NULL COMMENT '设备记录 ID（预留，去重）',
    note                    VARCHAR(200) NULL COMMENT '备注',
    recorded_by_people_id   VARCHAR(32)  NULL COMMENT '患者自录',
    recorded_by_staff_id    VARCHAR(32)  NULL COMMENT '员工代录',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_exercise_session_id (id),
    UNIQUE KEY uk_exercise_session_external (tenant_id, people_id, source, external_id, gmt_deleted),
    KEY idx_exercise_session_people_date (tenant_id, people_id, exercise_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运动记录';

CREATE TABLE IF NOT EXISTS people_allergy_profile (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '过敏史采集状态：UNKNOWN 未询问 / NKA 无已知过敏 / HAS 有过敏',
    reviewed_at             DATETIME     NULL COMMENT '医护最近核对时间',
    reviewed_by_staff_id    VARCHAR(32)  NULL COMMENT '医护最近核对人',
    updated_by_type         VARCHAR(16)  NULL COMMENT '最后修改方：STAFF/PEOPLE/SYSTEM',
    updated_by_id           VARCHAR(32)  NULL COMMENT '最后修改人',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_allergy_profile_id (id),
    UNIQUE KEY uk_allergy_profile_people (tenant_id, people_id, gmt_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者过敏史采集状态';

CREATE TABLE IF NOT EXISTS people_allergy (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    category                VARCHAR(16)  NOT NULL COMMENT 'DRUG/FOOD/ENVIRONMENT/OTHER',
    allergen_code           VARCHAR(48)  NULL COMMENT '过敏原字典编码；自由录入为空',
    allergen_name           VARCHAR(64)  NOT NULL COMMENT '过敏原名称',
    allergy_type            VARCHAR(16)  NOT NULL DEFAULT 'ALLERGY' COMMENT 'ALLERGY 过敏 / INTOLERANCE 不耐受',
    reactions_json          JSON         NULL COMMENT '反应表现编码数组',
    reaction_note           VARCHAR(128) NULL COMMENT '反应补充描述',
    severity                VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'MILD/MODERATE/SEVERE/UNKNOWN',
    high_risk               TINYINT      NOT NULL DEFAULT 0 COMMENT '高危：严重或出现休克/喉头水肿/呼吸困难',
    clinical_status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 仍过敏 / RESOLVED 已消失',
    verification            VARCHAR(16)  NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED 自述 / CONFIRMED 已确认 / REFUTED 已排除',
    onset_text              VARCHAR(32)  NULL COMMENT '首次发生时间（允许模糊）',
    last_occurrence         DATE         NULL COMMENT '最近一次发作',
    source                  VARCHAR(16)  NOT NULL COMMENT 'SELF 患者自填 / STAFF 医护录入 / IMPORT 历史迁移',
    note                    VARCHAR(200) NULL COMMENT '备注',
    verified_by_staff_id    VARCHAR(32)  NULL COMMENT '确认/排除人',
    verified_at             DATETIME     NULL COMMENT '确认/排除时间',
    created_by_type         VARCHAR(16)  NOT NULL COMMENT 'STAFF/PEOPLE/SYSTEM',
    created_by_id           VARCHAR(32)  NULL COMMENT '创建人',
    updated_by_type         VARCHAR(16)  NULL COMMENT '最后修改方',
    updated_by_id           VARCHAR(32)  NULL COMMENT '最后修改人',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_people_allergy_id (id),
    KEY idx_people_allergy_people (tenant_id, people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者过敏史条目';

-- ---------------------------------------------------------------- C 端激励（健康豆 / 火苗 / 徽章）

CREATE TABLE IF NOT EXISTS reward_point_ledger (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    biz_date                DATE         NOT NULL COMMENT '业务日期（豆记在此日）',
    biz_type                VARCHAR(16)  NOT NULL COMMENT 'PLAN/MED/VITAL/DIET/EXERCISE/FULL_DAY/BADGE',
    biz_ref                 VARCHAR(64)  NOT NULL COMMENT '源记录引用（同一引用的净额即应得）',
    seq                     INT          NOT NULL DEFAULT 0 COMMENT '同一引用的第几次调整；冲正追加新行',
    points                  INT          NOT NULL COMMENT '健康豆，冲正为负',
    title                   VARCHAR(64)  NOT NULL COMMENT '流水标题',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_reward_ledger_id (id),
    UNIQUE KEY uk_reward_ledger_ref (people_id, biz_type, biz_ref, seq),
    KEY idx_reward_ledger_people_date (people_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康豆流水';

CREATE TABLE IF NOT EXISTS reward_summary (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    level_max               INT          NOT NULL DEFAULT 1 COMMENT '历史最高等级（只升不降）',
    streak_days             INT          NOT NULL DEFAULT 0 COMMENT '已终结算的连续打卡天数',
    best_streak             INT          NOT NULL DEFAULT 0 COMMENT '最长连续天数',
    freeze_cards            INT          NOT NULL DEFAULT 0 COMMENT '保护卡（最多 2 张）',
    last_settled_date       DATE         NULL COMMENT '已终结算到哪天',
    last_push_date          DATE         NULL COMMENT '最近一次激励推送日（每日最多 1 条）',
    reward_disabled         TINYINT      NOT NULL DEFAULT 0 COMMENT '单患者关闭激励',
    peer_opt_out           TINYINT      NOT NULL DEFAULT 0 COMMENT '关闭同期百分位对比',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_reward_summary_id (id),
    UNIQUE KEY uk_reward_summary_people (people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者激励汇总';

CREATE TABLE IF NOT EXISTS reward_day (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    biz_date                DATE         NOT NULL COMMENT '结算日',
    active                  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否打卡日',
    full_day                TINYINT      NOT NULL DEFAULT 0 COMMENT '是否全勤日',
    due_count               INT          NOT NULL DEFAULT 0 COMMENT '应打方案任务数',
    done_count              INT          NOT NULL DEFAULT 0 COMMENT '本人完成方案任务数',
    freeze_used             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否消耗保护卡',
    streak_after            INT          NOT NULL DEFAULT 0 COMMENT '结算后连续天数',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_reward_day_id (id),
    UNIQUE KEY uk_reward_day_people (people_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='激励日结';

CREATE TABLE IF NOT EXISTS reward_badge (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    badge_code              VARCHAR(64)  NOT NULL COMMENT '徽章编码',
    badge_name              VARCHAR(64)  NOT NULL COMMENT '徽章名称（快照）',
    kind                    VARCHAR(16)  NOT NULL COMMENT 'BEHAVIOR 行为 / OUTCOME 结果荣誉',
    earned_date             DATE         NOT NULL COMMENT '获得日期',
    source_ref              VARCHAR(64)  NULL COMMENT '来源（结果徽章为报告ID）',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_reward_badge_id (id),
    UNIQUE KEY uk_reward_badge_people (people_id, badge_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='患者徽章';

CREATE TABLE IF NOT EXISTS reward_kudos (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NULL COMMENT '机构业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    staff_id                VARCHAR(32)  NOT NULL COMMENT '点赞健管师',
    kudos_date              DATE         NOT NULL COMMENT '点赞日（同人同日一次）',
    message                 VARCHAR(100) NULL COMMENT '鼓励语',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_reward_kudos_id (id),
    UNIQUE KEY uk_reward_kudos_day (people_id, staff_id, kudos_date),
    KEY idx_reward_kudos_people (people_id, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健管师点赞';

CREATE TABLE IF NOT EXISTS reward_challenge (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    period_start            DATE         NOT NULL COMMENT '周一开始',
    period_end              DATE         NOT NULL COMMENT '周日结束',
    kind                    VARCHAR(32)  NOT NULL COMMENT 'FULL_DAYS/VITAL_COUNT/PHOTO_MEAL',
    title                   VARCHAR(64)  NOT NULL COMMENT '展示标题',
    target                  INT          NOT NULL COMMENT '目标值',
    progress                INT          NOT NULL DEFAULT 0 COMMENT '当前进度',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DONE/FAILED',
    source                  VARCHAR(16)  NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/STAFF',
    points_awarded          INT          NOT NULL DEFAULT 0 COMMENT '结算发放豆数',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_reward_challenge_id (id),
    UNIQUE KEY uk_reward_challenge_week (people_id, period_start, kind),
    KEY idx_reward_challenge_people (people_id, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='周挑战';

CREATE TABLE IF NOT EXISTS water_intake_log (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    biz_date                DATE         NOT NULL COMMENT '业务日期',
    amount_ml               INT          NOT NULL COMMENT '本次毫升',
    source                  VARCHAR(16)  NOT NULL DEFAULT 'CUP' COMMENT 'CUP/CUSTOM/STAFF',
    recorded_by_people_id   VARCHAR(32)  NULL COMMENT '患者本人录入',
    recorded_by_staff_id    VARCHAR(32)  NULL COMMENT '员工代录',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_water_intake_id (id),
    KEY idx_water_intake_people_date (people_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='饮水记录';

CREATE TABLE IF NOT EXISTS sleep_log (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    biz_date                DATE         NOT NULL COMMENT '业务日期（起床日）',
    segment                 VARCHAR(16)  NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN主睡眠/NAP午睡',
    duration_min            INT          NOT NULL COMMENT '睡眠时长（分钟）',
    quality                 VARCHAR(16)  NOT NULL DEFAULT 'FAIR' COMMENT 'GOOD/FAIR/POOR',
    bedtime                 TIME         NULL COMMENT '入睡时间',
    wake_time               TIME         NULL COMMENT '起床时间',
    note                    VARCHAR(200) NULL COMMENT '备注',
    recorded_by_people_id   VARCHAR(32)  NULL COMMENT '患者本人录入',
    recorded_by_staff_id    VARCHAR(32)  NULL COMMENT '员工代录',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_sleep_log_id (id),
    KEY idx_sleep_log_people_date (people_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='睡眠记录（可多段）';

CREATE TABLE IF NOT EXISTS mood_log (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id               VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    biz_date                DATE         NOT NULL COMMENT '业务日期',
    score                   TINYINT      NOT NULL COMMENT '心情 1-5',
    tags_json               JSON         NULL COMMENT '标签数组',
    note                    VARCHAR(200) NULL COMMENT '备注',
    recorded_by_people_id   VARCHAR(32)  NULL COMMENT '患者本人录入',
    recorded_by_staff_id    VARCHAR(32)  NULL COMMENT '员工代录',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_mood_log_id (id),
    UNIQUE KEY uk_mood_log_people_date (people_id, biz_date, is_deleted, gmt_deleted),
    KEY idx_mood_log_people_date (people_id, biz_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='心情记录';

CREATE TABLE IF NOT EXISTS org_recipe (
    pk_id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                      VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id               VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id                  VARCHAR(32)  NOT NULL COMMENT '机构业务ID',
    name                    VARCHAR(64)  NOT NULL COMMENT '食谱名称',
    description             VARCHAR(255) NULL COMMENT '说明',
    tags_json               JSON         NULL COMMENT '标签',
    meal_json               JSON         NOT NULL COMMENT '示例日 sampleDay',
    week_json               JSON         NULL COMMENT '示例周 sampleWeek',
    energy_kcal_hint        INT          NULL COMMENT '热量提示',
    allergen_keywords_json  JSON         NULL COMMENT '过敏关键词提示',
    status                  VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    created_by_staff_id     VARCHAR(32)  NULL COMMENT '创建人',
    updated_by_staff_id     VARCHAR(32)  NULL COMMENT '更新人',
    is_deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted             DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_org_recipe_id (id),
    KEY idx_org_recipe_org (org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构食谱库';
