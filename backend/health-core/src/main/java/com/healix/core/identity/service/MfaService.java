package com.healix.core.identity.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.TotpSupport;
import com.healix.core.identity.domain.AccountMfa;
import com.healix.core.identity.mapper.AccountMfaMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** B/Ops 账号的 TOTP 二次校验：绑定、启用、关闭与登录校验。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    public static final String TYPE_STAFF = "STAFF";
    public static final String TYPE_OPS = "OPS";

    private static final String PENDING = "PENDING";
    private static final String ENABLED = "ENABLED";

    private final AccountMfaMapper accountMfaMapper;

    @Value("${healix.security.mfa.issuer:Healix}")
    private String issuer;

    /**
     * @param secret     仅在绑定阶段返回，启用后不再下发
     * @param otpAuthUri 供前端渲染二维码
     */
    public record MfaSetup(String secret, String otpAuthUri) {}

    /** @param bound 已生成过密钥（含待验证） */
    public record MfaStatus(boolean bound, boolean enabled) {}

    public MfaStatus status(String accountType, String accountId) {
        AccountMfa row = accountMfaMapper.find(accountType, accountId);
        if (row == null) {
            return new MfaStatus(false, false);
        }
        return new MfaStatus(true, ENABLED.equals(row.getStatus()));
    }

    public boolean enabled(String accountType, String accountId) {
        AccountMfa row = accountMfaMapper.find(accountType, accountId);
        return row != null && ENABLED.equals(row.getStatus());
    }

    /**
     * 生成新密钥并置为待验证。已启用的账号需先关闭再重新绑定，
     * 否则一次误触就会让原来的验证器失效、把自己锁在外面。
     */
    @Transactional
    public MfaSetup setup(String accountType, String accountId, String accountName) {
        AccountMfa existing = accountMfaMapper.find(accountType, accountId);
        if (existing != null && ENABLED.equals(existing.getStatus())) {
            throw new BusinessException("已启用二次校验，请先关闭后再重新绑定");
        }
        String secret = TotpSupport.generateSecret();
        AccountMfa row = new AccountMfa();
        row.setAccountType(accountType);
        row.setAccountId(accountId);
        row.setSecret(secret);
        row.setStatus(PENDING);
        row.setEnabledAt(null);
        EntityMeta.onCreate(row);
        accountMfaMapper.upsert(row);
        return new MfaSetup(secret, TotpSupport.otpAuthUri(issuer, accountName, secret));
    }

    @Transactional
    public void enable(String accountType, String accountId, String code) {
        AccountMfa row = accountMfaMapper.find(accountType, accountId);
        if (row == null) {
            throw new BusinessException("请先获取绑定密钥");
        }
        if (!TotpSupport.verify(row.getSecret(), code)) {
            throw new BusinessException("动态验证码不正确");
        }
        if (ENABLED.equals(row.getStatus())) {
            return;
        }
        accountMfaMapper.enable(accountType, accountId, LocalDateTime.now());
    }

    /** 关闭需要一次有效验证码：只凭登录态就能关，等于二次校验形同虚设。 */
    @Transactional
    public void disable(String accountType, String accountId, String code) {
        AccountMfa row = accountMfaMapper.find(accountType, accountId);
        if (row == null) {
            return;
        }
        if (ENABLED.equals(row.getStatus()) && !TotpSupport.verify(row.getSecret(), code)) {
            throw new BusinessException("动态验证码不正确");
        }
        accountMfaMapper.softDelete(accountType, accountId, LocalDateTime.now());
    }

    /**
     * 登录校验。未启用则直接放行；已启用且未带码时抛出提示，由前端补录入。
     */
    public void assertLoginCode(String accountType, String accountId, String code) {
        AccountMfa row = accountMfaMapper.find(accountType, accountId);
        if (row == null || !ENABLED.equals(row.getStatus())) {
            return;
        }
        if (code == null || code.isBlank()) {
            throw new BusinessException(401, "请输入动态验证码");
        }
        if (!TotpSupport.verify(row.getSecret(), code)) {
            throw new BusinessException(401, "动态验证码不正确");
        }
    }
}
