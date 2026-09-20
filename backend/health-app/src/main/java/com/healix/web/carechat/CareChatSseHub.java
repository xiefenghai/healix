package com.healix.web.carechat;

import com.healix.core.carechat.realtime.CareChatRealtimeEvent;
import com.healix.core.carechat.realtime.CareChatRealtimePublisher;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * CareChat 进程内 SSE Hub：按机构（B）/ 账号（C）扇出事件。
 *
 * <p>单机足够。多实例必须对 {@code /care-chat/events} 做 sticky session（见
 * {@code deploy/nginx-care-chat-sse.example.conf}），否则推送会落到无订阅的实例，只能靠轮询兜底。
 */
@Slf4j
@Component
public class CareChatSseHub implements CareChatRealtimePublisher {

    private static final long TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long HEARTBEAT_SEC = 15L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> orgEmitters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> accountEmitters =
            new ConcurrentHashMap<>();
    /** 全量 emitter，供单 ticker 心跳（避免每连接一个 ScheduledFuture） */
    private final ConcurrentHashMap<SseEmitter, Runnable> emitterCleanups = new ConcurrentHashMap<>();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "care-chat-sse-hb");
                t.setDaemon(true);
                return t;
            });

    public SseEmitter subscribeOrg(String tenantId, String orgId) {
        return subscribe(orgEmitters, key(tenantId, orgId));
    }

    public SseEmitter subscribeAccount(String tenantId, String accountId) {
        return subscribe(accountEmitters, key(tenantId, accountId));
    }

    @Override
    public void publish(CareChatRealtimeEvent event) {
        if (event == null) {
            return;
        }
        if (StringUtils.hasText(event.getTenantId()) && StringUtils.hasText(event.getOrgId())) {
            fanOut(orgEmitters.get(key(event.getTenantId(), event.getOrgId())), event);
        }
        List<String> accounts = event.getPatientAccountIds();
        if (accounts != null && StringUtils.hasText(event.getTenantId())) {
            for (String accountId : accounts) {
                if (!StringUtils.hasText(accountId)) {
                    continue;
                }
                fanOut(accountEmitters.get(key(event.getTenantId(), accountId)), event);
            }
        }
    }

    private SseEmitter subscribe(ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> map, String key) {
        ensureHeartbeat();
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        map.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        Runnable cleanup = () -> {
            emitterCleanups.remove(emitter);
            CopyOnWriteArrayList<SseEmitter> list = map.get(key);
            if (list != null) {
                list.remove(emitter);
                if (list.isEmpty()) {
                    map.remove(key, list);
                }
            }
        };
        emitterCleanups.put(emitter, cleanup);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data(Map.of("type", "ready", "ok", true), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            detachQuietly(emitter);
            safeCompleteWithError(emitter, e);
        }
        return emitter;
    }

    private void ensureHeartbeat() {
        if (!heartbeatStarted.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(
                () -> {
                    for (SseEmitter emitter : emitterCleanups.keySet()) {
                        try {
                            emitter.send(SseEmitter.event().comment("ping"));
                        } catch (Exception ex) {
                            // 浏览器关页/刷新后连接已断：只摘订阅，勿再 complete/flush
                            detachQuietly(emitter);
                            log.debug("care-chat sse heartbeat drop: {}", rootMessage(ex));
                        }
                    }
                },
                HEARTBEAT_SEC,
                HEARTBEAT_SEC,
                TimeUnit.SECONDS);
    }

    private void fanOut(CopyOnWriteArrayList<SseEmitter> list, CareChatRealtimeEvent event) {
        if (list == null || list.isEmpty()) {
            return;
        }
        String name = StringUtils.hasText(event.getType()) ? event.getType() : "message";
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(name).data(event, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                detachQuietly(emitter);
                log.debug("care-chat sse fanOut drop: {}", rootMessage(e));
            }
        }
    }

    /** 从索引移除；不触碰已失效的 HTTP 响应。 */
    private void detachQuietly(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        Runnable cleanup = emitterCleanups.remove(emitter);
        if (cleanup != null) {
            try {
                cleanup.run();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void safeCompleteWithError(SseEmitter emitter, Exception e) {
        try {
            emitter.completeWithError(e);
        } catch (Exception ignored) {
            // response 已不可用时忽略
        }
    }

    private static String rootMessage(Throwable ex) {
        if (ex == null) {
            return "";
        }
        if (ex instanceof AsyncRequestNotUsableException
                || ex.getClass().getName().contains("ClientAbortException")) {
            return "client disconnected";
        }
        String msg = ex.getMessage();
        return msg == null ? ex.getClass().getSimpleName() : msg;
    }

    private static String key(String tenantId, String id) {
        return tenantId + ":" + id;
    }
}
