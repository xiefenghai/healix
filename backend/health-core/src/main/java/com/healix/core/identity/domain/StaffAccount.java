package com.healix.core.identity.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** B 端登录账号 */
@Getter
@Setter
public class StaffAccount extends BaseEntity {
    /** 登录用户名 */
    private String username;
    /** 密码哈希 */
    private String passwordHash;
    /** 状态，见 {@link com.healix.core.identity.enums.EnableStatusEnum} */
    private String status;
}
