package com.healix.core.audit.mapper;

import com.healix.core.audit.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper {

    int insert(AuditLog log);
}
