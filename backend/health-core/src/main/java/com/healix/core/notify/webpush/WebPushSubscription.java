package com.healix.core.notify.webpush;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 浏览器推送订阅（按 C 账号；同一浏览器重复订阅按 endpoint 覆盖）。 */
@Getter
@Setter
public class WebPushSubscription extends BaseEntity {
    private String accountId;
    private String endpoint;
    /** endpoint 的 SHA-256 十六进制，唯一键（endpoint 过长不宜直接建索引） */
    private String endpointHash;
    private String p256dh;
    private String auth;
    private String userAgent;
    private int failCount;
    private LocalDateTime lastSuccessAt;
}
