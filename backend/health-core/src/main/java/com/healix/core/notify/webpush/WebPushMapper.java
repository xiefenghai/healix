package com.healix.core.notify.webpush;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WebPushMapper {

    /** 按 endpoint_hash 覆盖：换账号登录同一浏览器时订阅归属跟着走。 */
    int upsert(WebPushSubscription row);

    List<WebPushSubscription> listByAccount(@Param("accountId") String accountId);

    int countByAccount(@Param("accountId") String accountId);

    int deleteByEndpointHash(@Param("endpointHash") String endpointHash);

    int deleteByAccountAndHash(@Param("accountId") String accountId, @Param("endpointHash") String endpointHash);

    int markSuccess(@Param("id") String id);

    int markFailure(@Param("id") String id);

    /** 单行：未配置 VAPID 时首启自动生成并持久化。 */
    record VapidRow(String publicKey, String privateKey) {}

    VapidRow findVapid();

    int insertVapid(@Param("id") String id, @Param("publicKey") String publicKey, @Param("privateKey") String privateKey);
}
