package com.healix.core.audit.service;

import com.healix.common.domain.EntityMeta;
import com.healix.core.audit.domain.AuditLog;
import com.healix.core.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public void record(
            String portal,
            String actorAccountId,
            String actorType,
            String tenantId,
            String action,
            String resourceType,
            String resourceId,
            String patientId,
            String detailJson) {
        if (!StringUtils.hasText(actorAccountId)) {
            // actor_account_id NOT NULL；异步/系统回调未带账号时跳过，避免拖垮主流程
            log.warn(
                    "skip audit: missing actorAccountId action={} resourceType={} resourceId={}",
                    action,
                    resourceType,
                    resourceId);
            return;
        }
        AuditLog logRow = new AuditLog();
        logRow.setPortal(portal);
        logRow.setActorAccountId(actorAccountId.trim());
        logRow.setActorType(actorType);
        logRow.setTenantId(tenantId);
        logRow.setAction(action);
        logRow.setResourceType(resourceType);
        logRow.setResourceId(resourceId);
        logRow.setPeopleId(patientId);
        logRow.setDetailJson(detailJson);
        EntityMeta.onCreate(logRow);
        auditLogMapper.insert(logRow);
    }
}
