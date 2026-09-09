package com.healix.core.notify.mapper;

import com.healix.core.notify.domain.NotifyMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotifyMessageMapper {

    int insert(NotifyMessage row);

    int updateBodyKeepRead(NotifyMessage row);

    NotifyMessage findById(@Param("id") String id);

    NotifyMessage findByDedupe(
            @Param("tenantId") String tenantId,
            @Param("audience") String audience,
            @Param("recipientId") String recipientId,
            @Param("eventType") String eventType,
            @Param("dedupeKey") String dedupeKey);

    List<NotifyMessage> listByRecipient(
            @Param("tenantId") String tenantId,
            @Param("audience") String audience,
            @Param("recipientId") String recipientId,
            @Param("peopleId") String peopleId,
            @Param("unreadOnly") boolean unreadOnly,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countByRecipient(
            @Param("tenantId") String tenantId,
            @Param("audience") String audience,
            @Param("recipientId") String recipientId,
            @Param("peopleId") String peopleId,
            @Param("unreadOnly") boolean unreadOnly);

    int markRead(
            @Param("id") String id,
            @Param("recipientId") String recipientId,
            @Param("readAt") LocalDateTime readAt,
            @Param("gmtModified") LocalDateTime gmtModified);

    int markAllRead(
            @Param("tenantId") String tenantId,
            @Param("audience") String audience,
            @Param("recipientId") String recipientId,
            @Param("readAt") LocalDateTime readAt,
            @Param("gmtModified") LocalDateTime gmtModified);
}
