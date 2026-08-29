package com.healix.core.tenant.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 租户（PHI 共享边界） */
@Getter
@Setter
public class Tenant extends BaseEntity {
    /** 租户编码（唯一） */
    private String code;
    /** 租户名称 */
    private String name;
    /** 状态，见 {@link com.healix.core.tenant.enums.TenantStatusEnum} */
    private String status;
    /** 套餐编码 */
    private String planCode;
    /** 套餐/租约到期时间 */
    private LocalDateTime expireAt;
}
