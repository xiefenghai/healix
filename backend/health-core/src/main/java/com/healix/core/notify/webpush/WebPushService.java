package com.healix.core.notify.webpush;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.notify.domain.NotifyDelivery;
import com.healix.core.notify.domain.NotifyMessage;
import com.healix.core.notify.enums.NotifyChannel;
import com.healix.core.notify.enums.NotifyDeliveryStatus;
import com.healix.core.notify.mapper.NotifyDeliveryMapper;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 浏览器推送（RFC 8030/8291/8292）：订阅管理 + 加密投递。
 *
 * <p>只投递到主流浏览器推送服务的 endpoint（白名单），避免被当作 SSRF 跳板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebPushService {

    private static final List<String> DEFAULT_HOSTS = List.of(
            "fcm.googleapis.com",
            "android.googleapis.com",
            "push.services.mozilla.com",
            "push.apple.com",
            "notify.windows.com");
    private static final int MAX_FAILS = 10;
    private static final int MAX_BODY_CHARS = 200;
    private static final String PROVIDER = "webpush";

    private final WebPushMapper mapper;
    private final NotifyDeliveryMapper deliveryMapper;

    @Value("${healix.web-push.enabled:true}")
    private boolean enabled;

    @Value("${healix.web-push.vapid-public-key:}")
    private String configuredPublicKey;

    @Value("${healix.web-push.vapid-private-key:}")
    private String configuredPrivateKey;

    @Value("${healix.web-push.subject:mailto:support@healix.local}")
    private String subject;

    @Value("${healix.web-push.extra-allowed-hosts:}")
    private String extraAllowedHosts;

    @Value("${healix.web-push.ttl-seconds:86400}")
    private int ttlSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile WebPushCrypto.VapidKeys keys;

    public record Payload(String title, String body, String url, String tag) {}

    public record SendSummary(int total, int sent, int removed, int failed) {}

    public boolean isEnabled() {
        return enabled;
    }

    public String publicKey() {
        return keys().publicKey();
    }

    public int countSubscriptions(String accountId) {
        return mapper.countByAccount(accountId);
    }

    public void subscribe(String accountId, String endpoint, String p256dh, String auth, String userAgent) {
        if (!enabled) {
            throw new BusinessException(400, "推送未开启");
        }
        String ep = requireEndpoint(endpoint);
        validateKeys(p256dh, auth);
        WebPushSubscription row = new WebPushSubscription();
        EntityMeta.onCreate(row);
        row.setAccountId(accountId);
        row.setEndpoint(ep);
        row.setEndpointHash(sha256(ep));
        row.setP256dh(p256dh.trim());
        row.setAuth(auth.trim());
        row.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255)));
        mapper.upsert(row);
    }

    public void unsubscribe(String accountId, String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return;
        }
        mapper.deleteByAccountAndHash(accountId, sha256(endpoint.trim()));
    }

    /** 同步发送到该账号全部设备。 */
    public SendSummary sendToAccount(String accountId, Payload payload) {
        List<WebPushSubscription> subs = enabled ? mapper.listByAccount(accountId) : List.of();
        if (subs.isEmpty()) {
            return new SendSummary(0, 0, 0, 0);
        }
        byte[] json = JsonUtils.toJson(toMap(payload)).getBytes(StandardCharsets.UTF_8);
        int sent = 0;
        int removed = 0;
        int failed = 0;
        for (WebPushSubscription sub : subs) {
            switch (sendOne(sub, json)) {
                case SENT -> sent++;
                case GONE -> removed++;
                case FAILED -> failed++;
            }
        }
        return new SendSummary(subs.size(), sent, removed, failed);
    }

    /**
     * 消息落库事务内调用：先插 PENDING 投递记录，提交后异步发送并回写结果。
     * 无订阅时记为 SKIPPED。
     */
    public void dispatchAfterCommit(NotifyMessage message) {
        if (!enabled || !StringUtils.hasText(message.getRecipientId())) {
            return;
        }
        NotifyDelivery delivery = new NotifyDelivery();
        EntityMeta.onCreate(delivery);
        delivery.setTenantId(message.getTenantId());
        delivery.setMessageId(message.getId());
        delivery.setChannel(NotifyChannel.WEB_PUSH.name());
        delivery.setStatus(NotifyDeliveryStatus.PENDING.name());
        delivery.setAttemptCount(0);
        deliveryMapper.insert(delivery);

        // 经消息中心打开：标已读并切到消息归属就诊人后再跳 linkPath
        Payload payload = new Payload(
                message.getTitle(), message.getBody(), "/notifications?open=" + message.getId(), message.getEventType());
        Runnable task = () -> deliver(delivery, message.getRecipientId(), payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private void deliver(NotifyDelivery delivery, String accountId, Payload payload) {
        try {
            SendSummary s = sendToAccount(accountId, payload);
            delivery.setProvider(PROVIDER);
            delivery.setEndpointSnapshot("devices=" + s.total() + ",sent=" + s.sent() + ",removed=" + s.removed());
            if (s.total() == 0) {
                delivery.setStatus(NotifyDeliveryStatus.SKIPPED.name());
                delivery.setErrorCode("NO_SUBSCRIPTION");
            } else if (s.sent() > 0) {
                delivery.setStatus(NotifyDeliveryStatus.SENT.name());
                delivery.setSentAt(LocalDateTime.now());
            } else {
                delivery.setStatus(NotifyDeliveryStatus.FAILED.name());
                delivery.setErrorCode("ALL_FAILED");
            }
        } catch (Exception e) {
            log.warn("Web push deliver failed: message={}", delivery.getMessageId(), e);
            delivery.setStatus(NotifyDeliveryStatus.FAILED.name());
            delivery.setErrorCode("EXCEPTION");
            delivery.setErrorMessage(truncate(e.getMessage(), 500));
        }
        try {
            deliveryMapper.updateResult(delivery);
        } catch (Exception e) {
            log.warn("Web push delivery update failed: {}", delivery.getId(), e);
        }
    }

    private enum Outcome { SENT, GONE, FAILED }

    private Outcome sendOne(WebPushSubscription sub, byte[] json) {
        if (!isAllowedHost(URI.create(sub.getEndpoint()).getHost())) {
            mapper.deleteByEndpointHash(sub.getEndpointHash());
            return Outcome.GONE;
        }
        try {
            byte[] body = WebPushCrypto.encrypt(json, sub.getP256dh(), sub.getAuth());
            long exp = Instant.now().plus(Duration.ofHours(12)).getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(URI.create(sub.getEndpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", String.valueOf(Math.max(ttlSeconds, 0)))
                    .header("Urgency", "normal")
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Authorization", WebPushCrypto.vapidAuthorization(sub.getEndpoint(), subject, keys(), exp))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                mapper.markSuccess(sub.getId());
                return Outcome.SENT;
            }
            if (status == 404 || status == 410) {
                mapper.deleteByEndpointHash(sub.getEndpointHash());
                return Outcome.GONE;
            }
            log.info("Web push rejected: status={} host={}", status, URI.create(sub.getEndpoint()).getHost());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("Web push send error: {}", e.toString());
        }
        if (sub.getFailCount() + 1 >= MAX_FAILS) {
            mapper.deleteByEndpointHash(sub.getEndpointHash());
            return Outcome.GONE;
        }
        mapper.markFailure(sub.getId());
        return Outcome.FAILED;
    }

    private WebPushCrypto.VapidKeys keys() {
        WebPushCrypto.VapidKeys k = keys;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (keys != null) {
                return keys;
            }
            if (StringUtils.hasText(configuredPublicKey) && StringUtils.hasText(configuredPrivateKey)) {
                keys = new WebPushCrypto.VapidKeys(configuredPublicKey.trim(), configuredPrivateKey.trim());
                return keys;
            }
            WebPushMapper.VapidRow row = mapper.findVapid();
            if (row == null) {
                WebPushCrypto.VapidKeys gen = WebPushCrypto.generateVapidKeys();
                try {
                    mapper.insertVapid(SnowflakeId.nextBizId(), gen.publicKey(), gen.privateKey());
                    log.info("Generated Web Push VAPID keys");
                } catch (Exception e) {
                    log.warn("Insert VAPID keys failed, re-reading: {}", e.toString());
                }
                row = mapper.findVapid();
                if (row == null) {
                    throw new IllegalStateException("VAPID 密钥不可用");
                }
            }
            keys = new WebPushCrypto.VapidKeys(row.publicKey(), row.privateKey());
            return keys;
        }
    }

    private String requireEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint) || endpoint.length() > 1024) {
            throw new BusinessException(400, "订阅地址无效");
        }
        String ep = endpoint.trim();
        URI uri;
        try {
            uri = URI.create(ep);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "订阅地址无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !isAllowedHost(uri.getHost())) {
            throw new BusinessException(400, "当前浏览器的推送服务暂不支持");
        }
        return ep;
    }

    private static void validateKeys(String p256dh, String auth) {
        try {
            byte[] pub = WebPushCrypto.decode(p256dh.trim());
            byte[] secret = WebPushCrypto.decode(auth.trim());
            if (pub.length != 65 || pub[0] != 0x04 || secret.length != 16) {
                throw new IllegalArgumentException();
            }
            WebPushCrypto.decodePublic(pub);
        } catch (Exception e) {
            throw new BusinessException(400, "订阅密钥无效");
        }
    }

    private boolean isAllowedHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        List<String> allowed = new ArrayList<>(DEFAULT_HOSTS);
        if (StringUtils.hasText(extraAllowedHosts)) {
            for (String s : extraAllowedHosts.split(",")) {
                if (StringUtils.hasText(s)) {
                    allowed.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (String a : allowed) {
            if (h.equals(a) || h.endsWith("." + a)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> toMap(Payload p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", truncate(p.title(), 80));
        m.put("body", truncate(p.body(), MAX_BODY_CHARS));
        m.put("url", StringUtils.hasText(p.url()) ? p.url() : "/notifications");
        if (StringUtils.hasText(p.tag())) {
            m.put("tag", p.tag());
        }
        return m;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
