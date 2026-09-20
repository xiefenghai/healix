package com.healix.core.carechat.mapper;

import com.healix.core.carechat.domain.CareChatMessage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CareChatMessageMapper {

    CareChatMessage findById(@Param("id") String id);

    CareChatMessage findByClientMsgId(
            @Param("threadId") String threadId, @Param("clientMsgId") String clientMsgId);

    List<CareChatMessage> listHistory(
            @Param("threadId") String threadId,
            @Param("beforeAt") LocalDateTime beforeAt,
            @Param("beforeId") String beforeId,
            @Param("limit") int limit);

    List<CareChatMessage> listAfter(
            @Param("threadId") String threadId,
            @Param("afterAt") LocalDateTime afterAt,
            @Param("afterId") String afterId,
            @Param("limit") int limit);

    int insert(CareChatMessage row);
}
