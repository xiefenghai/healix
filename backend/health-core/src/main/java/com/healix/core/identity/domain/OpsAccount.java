package com.healix.core.identity.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** Ops 平台运维账号 */
@Getter
@Setter
public class OpsAccount extends BaseEntity {
    /** 登录用户名 */
    private String username;
    /** 密码哈希 */
    private String passwordHash;
    /** 显示名称 */
    private String displayName;
    /** 平台角色，见 {@link com.healix.core.identity.enums.OpsRoleEnum} */
    private String roleCode;
    /** 状态，见 {@link com.healix.core.identity.enums.EnableStatusEnum} */
    private String status;
}
