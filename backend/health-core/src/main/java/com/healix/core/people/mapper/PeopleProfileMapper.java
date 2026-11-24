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

    List<PeopleProfile> listBySelfMobile(
            @Param("tenantId") String tenantId, @Param("mobile") String mobile);

    /** 平台级：本人手机号下的档案（跨租户）。 */
    List<PeopleProfile> listBySelfMobileGlobal(@Param("mobile") String mobile);

    /** 平台级对齐键：本人手机号 + 姓名。 */
    PeopleProfile findBySelfMobileAndName(
            @Param("mobile") String mobile, @Param("displayName") String displayName);

    List<PeopleProfile> listPossibleDuplicates(
            @Param("tenantId") String tenantId,
            @Param("displayName") String displayName,
            @Param("birthday") java.time.LocalDate birthday,
            @Param("mobile") String mobile);

    int insert(PeopleProfile profile);

    int updateProfile(PeopleProfile profile);
}
