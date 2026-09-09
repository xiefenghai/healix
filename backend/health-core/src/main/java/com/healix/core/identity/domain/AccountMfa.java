package com.healix.core.identity.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * B/Ops 账号的 TOTP 二次校验绑定。
 *
 * <p>单独一张表而不是往两张账号表加列：绑定是可选能力，且 STAFF 与 OPS 的流程完全一致，
 * 一张表两种 accountType 就够，省掉两套读写。
 */
@Getter
@Setter
public class AccountMfa extends BaseEntity {
    /** STAFF / OPS */
    private String accountType;
    private String accountId;
    /** Base32 TOTP 密钥 */
    private String secret;
    /** PENDING 已生成待验证 / ENABLED 已启用 */
    private String status;
    private LocalDateTime enabledAt;
}
