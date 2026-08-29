package com.healix.core.audit.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 系统审计日志（含 Ops PHI 访问） */
@Getter
@Setter
public class AuditLog extends BaseEntity {
    /** 入口：C/B/OPS */
    private String portal;
    /** 操作者账号ID */
    private String actorAccountId;
    /** 操作者类型：OPS/STAFF/PATIENT */
    private String actorType;
    /** 相关租户ID */
    private String tenantId;
    /** 动作编码，如 OPS_PHI_VIEW */
    private String action;
    /** 资源类型 */
    private String resourceType;
    /** 资源ID */
    private String resourceId;
    /** 相关患者ID（PHI 访问时） */
    private String peopleId;
    /** 详情 JSON */
    private String detailJson;
    /** 客户端 IP */
    private String ip;
    /** User-Agent */
    private String userAgent;
}
