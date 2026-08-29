package com.healix.core.people.mapper;

import com.healix.core.people.domain.PeopleProfile;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleProfileMapper {

    PeopleProfile findById(@Param("id") String id);

    PeopleProfile findByAccountId(@Param("accountId") String accountId);

    List<PeopleProfile> searchByTenant(
            @Param("tenantId") String tenantId, @Param("keyword") String keyword, @Param("limit") int limit);

    int insert(PeopleProfile profile);

    int updateProfile(PeopleProfile profile);
}
