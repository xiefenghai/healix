package com.healix.core.carechat.realtime;

/**
 * 沟通域实时推送端口（V1/P1：由 app 层 SSE Hub 实现；无实现时 no-op）。
 */
public interface CareChatRealtimePublisher {

    void publish(CareChatRealtimeEvent event);
}
