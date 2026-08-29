package com.healix.core.audit.service;

import com.healix.common.domain.EntityMeta;
import com.healix.core.audit.domain.AuditLog;
import com.healix.core.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
        AuditLog log = new AuditLog();
        log.setPortal(portal);
        log.setActorAccountId(actorAccountId);
        log.setActorType(actorType);
        log.setTenantId(tenantId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setPeopleId(patientId);
        log.setDetailJson(detailJson);
        EntityMeta.onCreate(log);
        auditLogMapper.insert(log);
    }
}
