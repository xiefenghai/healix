package com.healix.core.carechat.enums;

/** 会话消息发送方。 */
public enum CareChatSenderType {
    STAFF,
    PATIENT;

    public static CareChatSenderType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return CareChatSenderType.valueOf(raw.trim());
    }
}
