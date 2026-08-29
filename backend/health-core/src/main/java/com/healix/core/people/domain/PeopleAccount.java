package com.healix.core.people.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** C 端登录账号（租户隔离） */
@Getter
@Setter
public class PeopleAccount extends BaseEntity {
    private String tenantId;
    private String username;
    private String passwordHash;
    private String status;
}
