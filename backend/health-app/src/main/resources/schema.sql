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
    allergens_json         JSON         NULL COMMENT '过敏原 JSON',
    chronic_tags_json      JSON         NULL COMMENT '慢性病标签 JSON',
    emergency_contact_json JSON         NULL COMMENT '紧急联系人 JSON',
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
    metric_type     VARCHAR(32)    NOT NULL COMMENT '指标类型',
    value           DECIMAL(12, 4) NOT NULL COMMENT '指标数值',
    unit            VARCHAR(16)    NULL COMMENT '单位',
    recorded_at     DATETIME       NOT NULL COMMENT '测量/记录时间',
    source          VARCHAR(32)    NOT NULL DEFAULT 'SELF' COMMENT '来源：SELF/DEVICE/STAFF',
    extra_json      JSON           NULL COMMENT '扩展信息 JSON',
    is_deleted      TINYINT        NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME       NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_vital_record_id (id),
    KEY idx_vital_tenant_people_metric_time (tenant_id, people_id, metric_type, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='体征记录（租户内共享）';

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
-- P2: 预留表
-- =============================================================================

CREATE TABLE IF NOT EXISTS health_report (
    pk_id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    title                VARCHAR(128) NOT NULL COMMENT '报告标题',
    report_type          VARCHAR(32)  NULL COMMENT '报告类型',
    storage_key          VARCHAR(512) NULL COMMENT '对象存储 Key/URL',
    parsed_summary_json  JSON         NULL COMMENT '解析摘要 JSON',
    report_date          DATETIME     NOT NULL COMMENT '报告日期',
    is_deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted          DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_health_report_id (id),
    KEY idx_report_tenant_people_date (tenant_id, people_id, report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康报告元数据（P2）';

CREATE TABLE IF NOT EXISTS alert_ticket (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    rule_code           VARCHAR(64)  NOT NULL COMMENT '规则编码',
    severity            VARCHAR(16)  NOT NULL COMMENT '严重级别',
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN' COMMENT '状态',
    message             VARCHAR(512) NOT NULL COMMENT '告警说明',
    assignee_staff_id   VARCHAR(32)  NULL COMMENT '处理人业务ID',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_alert_ticket_id (id),
    KEY idx_alert_org_status (org_id, status),
    KEY idx_alert_tenant_people (tenant_id, people_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预警工单（P2）';

CREATE TABLE IF NOT EXISTS follow_up_task (
    pk_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    assignee_staff_id   VARCHAR(32)  NULL COMMENT '负责人业务ID',
    title               VARCHAR(128) NOT NULL COMMENT '任务标题',
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    due_at              DATETIME     NULL COMMENT '截止时间',
    completed_at        DATETIME     NULL COMMENT '完成时间',
    is_deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted         DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_follow_up_task_id (id),
    KEY idx_followup_org_status (org_id, status),
    KEY idx_followup_assignee (assignee_staff_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='随访任务（P2）';

CREATE TABLE IF NOT EXISTS intervention_record (
    pk_id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键自增',
    id                  VARCHAR(32)  NOT NULL COMMENT '业务主键（雪花）',
    tenant_id           VARCHAR(32)  NOT NULL COMMENT '所属租户业务ID',
    org_id              VARCHAR(32)  NOT NULL COMMENT '所属机构业务ID',
    people_id           VARCHAR(32)  NOT NULL COMMENT '患者业务ID',
    staff_id            VARCHAR(32)  NOT NULL COMMENT '记录人业务ID',
    content         TEXT         NOT NULL COMMENT '干预内容',
    is_deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否删除：0未删除 1已删除',
    gmt_created     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    gmt_modified    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录修改时间',
    gmt_deleted     DATETIME     NOT NULL DEFAULT '9999-12-31 23:59:59' COMMENT '记录删除时间',
    PRIMARY KEY (pk_id),
    UNIQUE KEY uk_intervention_record_id (id),
    KEY idx_intervention_org_people (org_id, people_id, gmt_created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='干预记录（P2）';

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
    tenant_id           VARCHAR(32)  NULL COMMENT '租户业务ID；空表示平台级',
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
