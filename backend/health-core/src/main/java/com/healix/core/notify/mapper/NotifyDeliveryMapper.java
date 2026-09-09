package com.healix.core.notify.mapper;

import com.healix.core.notify.domain.NotifyDelivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotifyDeliveryMapper {

    int insert(NotifyDelivery row);

    NotifyDelivery findByMessageAndChannel(
            @Param("messageId") String messageId, @Param("channel") String channel);
}
