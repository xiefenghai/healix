package com.healix.core.patientcard.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.patientcard.domain.MobileOtp;
import com.healix.core.patientcard.mapper.MobileOtpMapper;
import com.healix.core.people.support.MobileNumbers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 本人手机号验证码。未接短信网关时只记日志，接口不回传验证码。 */
@Slf4j
@Service
public class MobileOtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TTL_MINUTES = 5;

    private final MobileOtpMapper otpMapper;
    private final String pepper;
    private final boolean logCode;
    private final String mockCode;

    public MobileOtpService(
            MobileOtpMapper otpMapper,
            @Value("${healix.security.id-card-pepper}") String pepper,
            @Value("${healix.sms.log-code:false}") boolean logCode,
            @Value("${healix.sms.mock-code:}") String mockCode) {
        this.otpMapper = otpMapper;
        this.pepper = pepper;
        this.logCode = logCode;
        this.mockCode = mockCode == null ? "" : mockCode.trim();
    }

    @Transactional
    public void send(String tenantId, String accountId, String rawMobile) {
        String mobile = MobileNumbers.requireSelf(rawMobile);
        if (StringUtils.hasText(mockCode)) {
            log.info("mock sms mobile={} code={}", mobile, mockCode);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        MobileOtp latest = otpMapper.findLatestAny(tenantId, accountId, mobile);
        if (latest != null && latest.getGmtCreated() != null && latest.getGmtCreated().plusSeconds(60).isAfter(now)) {
            throw new BusinessException(429, "验证码已发送，请稍后再试");
        }
        LocalDateTime start = LocalDate.now().atTime(LocalTime.MIN);
        if (otpMapper.countSince(tenantId, mobile, start) >= 10) {
            throw new BusinessException(429, "该号码今日验证码次数已达上限");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otpMapper.consumeUnspent(tenantId, accountId, mobile, now);
        MobileOtp row = new MobileOtp();
        row.setTenantId(tenantId);
        row.setAccountId(accountId);
        row.setMobile(mobile);
        row.setCodeHash(hash(mobile, code));
        row.setExpireAt(now.plusMinutes(TTL_MINUTES));
        EntityMeta.onCreate(row);
        otpMapper.insert(row);
        if (logCode) {
            log.warn("mobile otp tenant={} mobile={} code={}", tenantId, mobile, code);
        }
    }

    @Transactional
    public String verify(String tenantId, String accountId, String rawMobile, String code) {
        String mobile = MobileNumbers.requireSelf(rawMobile);
        if (StringUtils.hasText(mockCode)) {
            if (!mockCode.equals(code == null ? "" : code.trim())) {
                throw new BusinessException(400, "验证码不正确");
            }
            return mobile;
        }
        if (!StringUtils.hasText(code) || !code.trim().matches("\\d{6}")) {
            throw new BusinessException(400, "请输入 6 位验证码");
        }
        MobileOtp row = otpMapper.findLatestOpen(tenantId, accountId, mobile);
        LocalDateTime now = LocalDateTime.now();
        if (row == null || row.getExpireAt() == null || row.getExpireAt().isBefore(now)) {
            throw new BusinessException(400, "验证码已失效，请重新获取");
        }
        if (!hash(mobile, code.trim()).equals(row.getCodeHash())) {
            throw new BusinessException(400, "验证码不正确");
        }
        if (otpMapper.consume(row.getId(), now) == 0) {
            throw new BusinessException(400, "验证码已失效，请重新获取");
        }
        return mobile;
    }

    private String hash(String mobile, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((pepper + ":" + mobile + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("验证码哈希失败", e);
        }
    }
}
