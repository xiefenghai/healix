package com.healix.core.notify.mapper;

import com.healix.core.notify.domain.NotifyDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotifyDeliveryMapper {

    int insert(NotifyDelivery row);

    /** 回写外部通道结果；attempt_count 自增。 */
    int updateResult(NotifyDelivery row);

    NotifyDelivery findByMessageAndChannel(
            @Param("messageId") String messageId, @Param("channel") String channel);
}
