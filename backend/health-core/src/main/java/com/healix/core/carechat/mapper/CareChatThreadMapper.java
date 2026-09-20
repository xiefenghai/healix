package com.healix.core.carechat.mapper;

import com.healix.core.carechat.domain.CareChatThread;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CareChatThreadMapper {

    CareChatThread findById(@Param("id") String id);

    CareChatThread findByOrgPeople(
            @Param("tenantId") String tenantId, @Param("orgId") String orgId, @Param("peopleId") String peopleId);

    List<CareChatThread> listByPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") List<String> peopleIds);

    List<CareChatThread> listByOrg(@Param("tenantId") String tenantId, @Param("orgId") String orgId);

    int insert(CareChatThread row);

    int updateAfterSend(
            @Param("id") String id,
            @Param("lastMessageAt") LocalDateTime lastMessageAt,
            @Param("lastMessagePreview") String lastMessagePreview,
            @Param("lastSenderType") String lastSenderType,
            @Param("incStaffUnread") int incStaffUnread,
            @Param("incPatientUnread") int incPatientUnread);

    int clearStaffUnread(@Param("id") String id);

    int clearPatientUnread(@Param("id") String id);

    long sumStaffUnreadByOrg(@Param("tenantId") String tenantId, @Param("orgId") String orgId);
}
