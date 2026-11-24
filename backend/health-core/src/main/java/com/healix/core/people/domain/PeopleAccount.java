package com.healix.core.people.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** C 端登录账号（平台级；tenantId 历史可空，不再参与唯一约束） */
@Getter
@Setter
public class PeopleAccount extends BaseEntity {
    /** @deprecated C 账号无租户；字段仅兼容旧数据 */
    private String tenantId;
    private String username;
    private String passwordHash;
    private String status;
}
