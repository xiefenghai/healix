package com.healix.core.allergy.mapper;

import com.healix.core.allergy.domain.PeopleAllergy;
import com.healix.core.people.domain.PeopleBasicArchive;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleAllergyMapper {

    int insert(PeopleAllergy row);

    int update(PeopleAllergy row);

    int softDelete(@Param("id") String id);

    PeopleAllergy findById(@Param("id") String id);

    List<PeopleAllergy> listByPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    /** 跨租户读取：安全校验只拿到 peopleId 时使用（people 本身属于唯一租户）。 */
    List<PeopleAllergy> listByPeopleId(@Param("peopleId") String peopleId);

    /** 基础档案既往史里仍带过敏条目、且尚未建立过敏史状态的患者（迁移用）。 */
    List<PeopleBasicArchive> listLegacyArchives();
}
