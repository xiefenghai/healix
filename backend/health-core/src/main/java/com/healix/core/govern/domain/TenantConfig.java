package com.healix.core.govern.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 租户白标配置（一租户一行），不含计费信息。 */
@Getter
@Setter
public class TenantConfig extends BaseEntity {
    private String tenantId;
    /** 对外应用名，B/C 端标题与登录页使用 */
    private String appName;
    private String logoUrl;
    /** 主色，形如 #2b9e9e */
    private String primaryColor;
    /** 登录页副标题 */
    private String loginSlogan;
    /** 客服电话 */
    private String supportPhone;
}
