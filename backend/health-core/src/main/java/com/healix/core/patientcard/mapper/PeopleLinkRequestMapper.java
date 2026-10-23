package com.healix.core.patientcard.mapper;

import com.healix.core.patientcard.domain.PeopleLinkRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleLinkRequestMapper {

    int insert(PeopleLinkRequest row);

    int update(PeopleLinkRequest row);

    PeopleLinkRequest findById(@Param("id") String id);

    PeopleLinkRequest findOpenLink(
            @Param("tenantId") String tenantId,
            @Param("accountId") String accountId,
            @Param("mobile") String mobile);

    PeopleLinkRequest findOpenDemographic(
            @Param("accountId") String accountId, @Param("peopleId") String peopleId);

    int countOpenLink(@Param("accountId") String accountId);

    List<PeopleLinkRequest> listVisible(@Param("accountId") String accountId);

    List<PeopleLinkRequest> listExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
