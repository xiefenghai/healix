package com.healix.core.people.mapper;

import com.healix.core.people.domain.PeopleProfile;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleProfileMapper {

    PeopleProfile findById(@Param("id") String id);

    PeopleProfile findByAccountId(@Param("accountId") String accountId);

    /** 含已软删；合并留痕的档案要能查到，才能给出「已合并到 X」这种可行动的提示。 */
    PeopleProfile findAnyById(@Param("id") String id);

    List<PeopleProfile> searchByTenant(
            @Param("tenantId") String tenantId, @Param("keyword") String keyword, @Param("limit") int limit);

    int insert(PeopleProfile profile);

    int updateProfile(PeopleProfile profile);
}
